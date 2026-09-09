package no.dentline.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

enum class NotificationType {
    BOOKING_RECEIVED,
    BOOKING_CONFIRMED,
    BOOKING_RESCHEDULED,
    BOOKING_CANCELLED,
    REMINDER,
    RECALL,
    WAITLIST_OFFER,
    WAITLIST_OFFER_EXPIRED,
}

enum class Channel { SMS, EMAIL }

/**
 * A message that would have been sent. Delivery is simulated; the row is the record of it.
 * [sourceEventId] is unique so a redelivered Kafka event cannot produce a second notification.
 */
@Entity
@Table(name = "notification")
class Notification(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "source_event_id", nullable = false, unique = true)
    val sourceEventId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: NotificationType,
    channels: List<Channel>,
    @Column(name = "recipient_name", nullable = false)
    val recipientName: String,
    @Column(name = "recipient_phone", nullable = false)
    val recipientPhone: String,
    @Column(name = "recipient_email", nullable = false)
    val recipientEmail: String,
    @Column(nullable = false)
    val subject: String,
    @Column(nullable = false)
    val body: String,
    @Column(name = "appointment_id")
    val appointmentId: UUID?,
    @Column(name = "waitlist_entry_id")
    val waitlistEntryId: UUID?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Column(name = "channels", nullable = false)
    private val channelsCsv: String = channels.joinToString(",") { it.name }

    val channels: List<Channel> get() = channelsCsv.split(',').filter { it.isNotBlank() }.map(Channel::valueOf)
}

interface NotificationRepository : JpaRepository<Notification, UUID> {
    fun existsBySourceEventId(sourceEventId: UUID): Boolean
    fun findByAppointmentIdOrderByCreatedAtDesc(appointmentId: UUID, pageable: Pageable): List<Notification>
    fun findByWaitlistEntryIdOrderByCreatedAtDesc(waitlistEntryId: UUID, pageable: Pageable): List<Notification>
    fun findByRecipientEmailIgnoreCaseOrderByCreatedAtDesc(email: String, pageable: Pageable): List<Notification>
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<Notification>
}
