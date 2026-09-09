package no.dentline.notification.events

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The parts of booking-service's events this service reads. Unknown fields are ignored, so the
 * producer can grow its payloads without breaking this consumer.
 */
data class PatientPayload(val name: String, val phone: String, val email: String)

data class AppointmentPayload(
    val id: UUID,
    val reference: String,
    val status: String,
    val practitionerName: String,
    val treatmentName: String,
    val durationMinutes: Int,
    val startTime: Instant,
    val endTime: Instant,
    val patient: PatientPayload,
)

data class WindowPayload(val startTime: Instant, val endTime: Instant)

data class AppointmentEventMessage(
    val eventId: UUID,
    val type: String,
    val occurredAt: Instant,
    val appointment: AppointmentPayload,
    val previous: WindowPayload? = null,
    val cancelledBy: String? = null,
)

data class WaitlistEntryPayload(
    val id: UUID,
    val status: String,
    val patient: PatientPayload,
    val treatmentName: String,
    val position: Int? = null,
    val queueSize: Int = 0,
)

data class SlotOfferPayload(
    val practitionerName: String,
    val startTime: Instant,
    val endTime: Instant,
    val expiresAt: Instant,
)

data class WaitlistEventMessage(
    val eventId: UUID,
    val type: String,
    val occurredAt: Instant,
    val entry: WaitlistEntryPayload,
    val offer: SlotOfferPayload? = null,
    val appointmentId: UUID? = null,
)

data class RecallEventMessage(
    val eventId: UUID,
    val type: String,
    val occurredAt: Instant,
    val patient: PatientPayload,
    val lastVisitDate: LocalDate,
    val dueDate: LocalDate,
    val practitionerName: String,
)
