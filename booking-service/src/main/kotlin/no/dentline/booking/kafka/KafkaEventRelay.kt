package no.dentline.booking.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import no.dentline.booking.event.AppointmentEvent
import no.dentline.booking.event.RecallEvent
import no.dentline.booking.event.WaitlistEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/**
 * Bridges in-process domain events to Kafka **after the transaction commits**, so a consumer
 * never reacts to a booking that was rolled back. Without an outbox this is still at-least-once
 * with a small window for loss if the process dies between commit and send; see README,
 * "Next steps for production".
 */
@Component
class KafkaEventRelay(
    private val kafka: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun relay(event: AppointmentEvent) =
        send(Topics.APPOINTMENT_EVENTS, key = event.appointment.id, eventId = event.eventId, type = event.type.name, payload = event)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun relay(event: WaitlistEvent) =
        send(Topics.WAITLIST_EVENTS, key = event.entry.id, eventId = event.eventId, type = event.type.name, payload = event)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun relay(event: RecallEvent) =
        send(Topics.RECALL_EVENTS, key = event.patient.email.lowercase(), eventId = event.eventId, type = event.type.name, payload = event)

    private fun send(topic: String, key: Any, eventId: UUID, type: String, payload: Any) {
        val json = objectMapper.writeValueAsString(payload)
        kafka.send(topic, key.toString(), json).whenComplete { result, failure ->
            if (failure != null) {
                log.error("Failed to publish {} {} to {}", type, eventId, topic, failure)
            } else {
                log.debug("Published {} {} to {}@{}", type, eventId, topic, result.recordMetadata.offset())
            }
        }
    }
}
