package no.dentline.booking.event

import no.dentline.booking.domain.Appointment
import no.dentline.booking.domain.AppointmentStatus
import java.time.Instant
import java.util.UUID

/** Types on the `appointment-events` topic. */
enum class AppointmentEventType { BOOKED, CONFIRMED, RESCHEDULED, CANCELLED, COMPLETED, NO_SHOW, REMINDER_DUE }

enum class CancelledBy { PATIENT, STAFF }

/**
 * Published in-process (Spring application event) by the services; a transactional listener
 * relays it to Kafka after commit so consumers never see events for rolled-back writes.
 */
data class AppointmentEvent(
    val type: AppointmentEventType,
    val occurredAt: Instant,
    val appointment: AppointmentSnapshot,
    /** For RESCHEDULED: the window that was freed. */
    val previous: WindowSnapshot? = null,
    /** For CANCELLED. */
    val cancelledBy: CancelledBy? = null,
    val eventId: UUID = UUID.randomUUID(),
)

data class AppointmentSnapshot(
    val id: UUID,
    val reference: String,
    val status: AppointmentStatus,
    val practitionerId: UUID,
    val practitionerName: String,
    val treatmentTypeId: UUID,
    val treatmentName: String,
    val durationMinutes: Int,
    val startTime: Instant,
    val endTime: Instant,
    val patient: PatientSnapshot,
) {
    companion object {
        fun of(appointment: Appointment) = AppointmentSnapshot(
            id = appointment.id,
            reference = appointment.reference,
            status = appointment.status,
            practitionerId = appointment.practitioner.id,
            practitionerName = appointment.practitioner.name,
            treatmentTypeId = appointment.treatmentType.id,
            treatmentName = appointment.treatmentType.name,
            durationMinutes = appointment.treatmentType.durationMinutes,
            startTime = appointment.startTime,
            endTime = appointment.endTime,
            patient = PatientSnapshot(appointment.patient.name, appointment.patient.phone, appointment.patient.email),
        )
    }
}

data class PatientSnapshot(val name: String, val phone: String, val email: String)

data class WindowSnapshot(val practitionerId: UUID, val startTime: Instant, val endTime: Instant)
