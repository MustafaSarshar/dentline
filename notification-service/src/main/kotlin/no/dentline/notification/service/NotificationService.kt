package no.dentline.notification.service

import io.micrometer.core.instrument.MeterRegistry
import no.dentline.notification.domain.Notification
import no.dentline.notification.domain.NotificationRepository
import no.dentline.notification.domain.NotificationType
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class NotificationService(private val notifications: NotificationRepository, private val registry: MeterRegistry) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        // Register every series up front so the dashboard shows zeros instead of "no data".
        NotificationType.entries.forEach { registry.counter("dentline.notifications", "type", it.name) }
    }

    /**
     * Stores the notification once per source event. Kafka delivers at least once, so the
     * check plus the unique constraint make this consumer idempotent.
     */
    @Transactional
    fun record(sourceEventId: UUID, draft: Draft, occurredAt: Instant): Notification? {
        if (notifications.existsBySourceEventId(sourceEventId)) {
            log.info("Event {} already produced a notification; ignoring redelivery", sourceEventId)
            return null
        }
        val notification = Notification(
            sourceEventId = sourceEventId,
            type = draft.type,
            channels = draft.channels,
            recipientName = draft.recipient.name,
            recipientPhone = draft.recipient.phone,
            recipientEmail = draft.recipient.email,
            subject = draft.subject,
            body = draft.body,
            appointmentId = draft.appointmentId,
            waitlistEntryId = draft.waitlistEntryId,
            createdAt = occurredAt,
        )
        return try {
            notifications.save(notification).also {
                log.info("[{}] to {} via {}: {}", it.type, it.recipientEmail, it.channels, it.subject)
                registry.counter("dentline.notifications", "type", it.type.name).increment()
            }
        } catch (e: DataIntegrityViolationException) {
            log.info("Event {} was recorded concurrently; ignoring", sourceEventId)
            null
        }
    }

    @Transactional(readOnly = true)
    fun find(appointmentId: UUID?, waitlistEntryId: UUID?, email: String?, limit: Int): List<Notification> {
        val page = PageRequest.of(0, limit.coerceIn(1, 200))
        return when {
            appointmentId != null -> notifications.findByAppointmentIdOrderByCreatedAtDesc(appointmentId, page)
            waitlistEntryId != null -> notifications.findByWaitlistEntryIdOrderByCreatedAtDesc(waitlistEntryId, page)
            !email.isNullOrBlank() -> notifications.findByRecipientEmailIgnoreCaseOrderByCreatedAtDesc(email.trim(), page)
            else -> notifications.findAllByOrderByCreatedAtDesc(page)
        }
    }
}
