package no.dentline.booking.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.dentline.booking.event.AppointmentEvent
import no.dentline.booking.event.AppointmentEventType
import no.dentline.booking.service.WaitlistMatcher
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * booking-service consumes its own appointment events to run waitlist matching. Doing this
 * through Kafka rather than inline keeps cancellation fast and makes matching retryable; the
 * matcher is idempotent because a pending offer blocks the window it was made for.
 */
@Component
class FreedWindowConsumer(private val matcher: WaitlistMatcher, private val objectMapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [Topics.APPOINTMENT_EVENTS], groupId = "booking-waitlist-matcher")
    fun onAppointmentEvent(payload: String) {
        val event = objectMapper.readValue<AppointmentEvent>(payload)
        when (event.type) {
            AppointmentEventType.CANCELLED -> {
                val a = event.appointment
                log.info("Appointment {} cancelled; looking for a waitlist match for {}–{}", a.reference, a.startTime, a.endTime)
                matcher.offerFreedWindow(a.practitionerId, a.startTime, a.endTime)
            }
            AppointmentEventType.RESCHEDULED -> event.previous?.let { freed ->
                log.info("Appointment {} moved; looking for a waitlist match for the freed window {}–{}", event.appointment.reference, freed.startTime, freed.endTime)
                matcher.offerFreedWindow(freed.practitionerId, freed.startTime, freed.endTime)
            }
            else -> Unit
        }
    }
}
