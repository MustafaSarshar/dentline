package no.dentline.notification.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.dentline.notification.events.AppointmentEventMessage
import no.dentline.notification.events.RecallEventMessage
import no.dentline.notification.events.WaitlistEventMessage
import no.dentline.notification.service.NotificationComposer
import no.dentline.notification.service.NotificationService
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * One listener per topic. Each event becomes at most one notification; events with no
 * patient-facing message (COMPLETED, NO_SHOW, ENTRY_CREATED, …) are acknowledged and dropped.
 */
@Component
class EventConsumers(
    private val composer: NotificationComposer,
    private val notifications: NotificationService,
    private val objectMapper: ObjectMapper,
) {
    @KafkaListener(topics = ["appointment-events"])
    fun onAppointmentEvent(payload: String) {
        val event = objectMapper.readValue<AppointmentEventMessage>(payload)
        composer.forAppointment(event)?.let { notifications.record(event.eventId, it, event.occurredAt) }
    }

    @KafkaListener(topics = ["waitlist-events"])
    fun onWaitlistEvent(payload: String) {
        val event = objectMapper.readValue<WaitlistEventMessage>(payload)
        composer.forWaitlist(event)?.let { notifications.record(event.eventId, it, event.occurredAt) }
    }

    @KafkaListener(topics = ["recall-events"])
    fun onRecallEvent(payload: String) {
        val event = objectMapper.readValue<RecallEventMessage>(payload)
        composer.forRecall(event)?.let { notifications.record(event.eventId, it, event.occurredAt) }
    }
}
