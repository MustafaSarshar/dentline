package no.dentline.booking.service

import no.dentline.booking.domain.Appointment
import no.dentline.booking.domain.ConflictCode
import no.dentline.booking.domain.DomainConflictException
import no.dentline.booking.domain.NotFoundException
import no.dentline.booking.domain.Patient
import no.dentline.booking.domain.Practitioner
import no.dentline.booking.event.AppointmentEvent
import no.dentline.booking.event.AppointmentEventType
import no.dentline.booking.event.AppointmentSnapshot
import no.dentline.booking.event.CancelledBy
import no.dentline.booking.event.WindowSnapshot
import no.dentline.booking.repository.AppointmentRepository
import no.dentline.booking.repository.PractitionerRepository
import no.dentline.booking.repository.SlotOfferRepository
import no.dentline.booking.repository.TreatmentTypeRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class BookAppointment(
    val treatmentTypeId: UUID,
    val practitionerId: UUID,
    val startTime: Instant,
    val patient: Patient,
)

@Service
class AppointmentService(
    private val appointments: AppointmentRepository,
    private val practitioners: PractitionerRepository,
    private val treatmentTypes: TreatmentTypeRepository,
    private val slotOffers: SlotOfferRepository,
    private val clinicTime: ClinicTime,
    private val events: ApplicationEventPublisher,
) {
    /**
     * Books a slot. The practitioner row is locked (`SELECT … FOR UPDATE`) before the overlap
     * check, so two concurrent requests for the same window are serialised: the second one
     * waits, then sees the first booking and fails with `SLOT_TAKEN`.
     */
    @Transactional
    fun book(command: BookAppointment): Appointment {
        val treatment = treatmentTypes.findById(command.treatmentTypeId)
            .orElseThrow { NotFoundException("Treatment type", command.treatmentTypeId) }
        val practitioner = practitioners.lockForUpdate(command.practitionerId)
            ?: throw NotFoundException("Practitioner", command.practitionerId)

        val end = command.startTime + treatment.duration
        ensureBookable(practitioner, command.startTime, end, ignoreAppointmentId = null)

        val now = clinicTime.now()
        val appointment = Appointment.book(
            reference = nextReference(),
            practitioner = practitioner,
            treatmentType = treatment,
            patient = command.patient,
            startTime = command.startTime,
            now = now,
        )
        appointments.save(appointment)
        publish(AppointmentEventType.BOOKED, appointment, now)
        return appointment
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): Appointment = appointments.findById(id).orElseThrow { NotFoundException("Appointment", id) }

    @Transactional(readOnly = true)
    fun getWithHistory(id: UUID): Appointment = get(id).also { it.history.size } // initialise the lazy collection

    @Transactional
    fun confirm(id: UUID): Appointment = transition(id, AppointmentEventType.CONFIRMED) { a, now -> a.confirm(now, STAFF) }

    @Transactional
    fun complete(id: UUID): Appointment = transition(id, AppointmentEventType.COMPLETED) { a, now -> a.complete(now, STAFF) }

    @Transactional
    fun markNoShow(id: UUID): Appointment = transition(id, AppointmentEventType.NO_SHOW) { a, now -> a.markNoShow(now, STAFF) }

    @Transactional
    fun cancel(id: UUID, by: CancelledBy): Appointment {
        val actor = if (by == CancelledBy.PATIENT) PATIENT else STAFF
        return transition(id, AppointmentEventType.CANCELLED, cancelledBy = by) { a, now -> a.cancel(now, actor) }
    }

    /**
     * Moves an appointment atomically: the old window is only released if the new one can be
     * taken. Locks the appointment, then the practitioner(s) involved in id order so two
     * reschedules crossing each other cannot deadlock.
     */
    @Transactional
    fun reschedule(id: UUID, newStart: Instant, newPractitionerId: UUID?): Appointment {
        val appointment = appointments.lockForUpdate(id) ?: throw NotFoundException("Appointment", id)
        val targetId = newPractitionerId ?: appointment.practitioner.id
        val locked = setOf(appointment.practitioner.id, targetId).sorted().associateWith { practitionerId ->
            practitioners.lockForUpdate(practitionerId) ?: throw NotFoundException("Practitioner", practitionerId)
        }
        val target = locked.getValue(targetId)

        ensureBookable(target, newStart, newStart + appointment.treatmentType.duration, ignoreAppointmentId = appointment.id)

        val previous = WindowSnapshot(appointment.practitioner.id, appointment.startTime, appointment.endTime)
        val now = clinicTime.now()
        appointment.reschedule(newStart, target, now, PATIENT)
        publish(AppointmentEventType.RESCHEDULED, appointment, now, previous = previous)
        return appointment
    }

    /**
     * The three checks behind a 409 on booking. Must run while the practitioner is locked.
     * [ignoreAppointmentId] excludes the appointment being rescheduled; [ignoreOfferId] excludes
     * the pending offer being accepted (it is what holds the window).
     */
    fun ensureBookable(
        practitioner: Practitioner,
        start: Instant,
        end: Instant,
        ignoreAppointmentId: UUID?,
        ignoreOfferId: UUID? = null,
    ) {
        if (!start.isAfter(clinicTime.now())) {
            throw DomainConflictException(ConflictCode.IN_THE_PAST, "That time has already passed")
        }
        val localStart = clinicTime.toLocal(start)
        val localEnd = clinicTime.toLocal(end)
        val insideWorkingHours = localStart.toLocalDate() == localEnd.toLocalDate() &&
            practitioner.workingWindowsOn(localStart.dayOfWeek).any { window ->
                !localStart.toLocalTime().isBefore(window.start) && !localEnd.toLocalTime().isAfter(window.end)
            }
        if (!insideWorkingHours) {
            throw DomainConflictException(ConflictCode.OUTSIDE_WORKING_HOURS, "${practitioner.name} is not working at that time")
        }
        val clash = appointments.findOverlapping(practitioner.id, start, end).any { it.id != ignoreAppointmentId } ||
            slotOffers.findPendingOverlapping(practitioner.id, start, end).any { it.id != ignoreOfferId }
        if (clash) {
            throw DomainConflictException(ConflictCode.SLOT_TAKEN, "That time is no longer available")
        }
    }

    fun nextReference(): String = "DL-%04d".format(appointments.nextReferenceNumber())

    private fun transition(
        id: UUID,
        type: AppointmentEventType,
        cancelledBy: CancelledBy? = null,
        action: (Appointment, Instant) -> Unit,
    ): Appointment {
        val appointment = appointments.lockForUpdate(id) ?: throw NotFoundException("Appointment", id)
        val now = clinicTime.now()
        action(appointment, now)
        publish(type, appointment, now, cancelledBy = cancelledBy)
        return appointment
    }

    private fun publish(
        type: AppointmentEventType,
        appointment: Appointment,
        occurredAt: Instant,
        previous: WindowSnapshot? = null,
        cancelledBy: CancelledBy? = null,
    ) {
        events.publishEvent(
            AppointmentEvent(
                type = type,
                occurredAt = occurredAt,
                appointment = AppointmentSnapshot.of(appointment),
                previous = previous,
                cancelledBy = cancelledBy,
            ),
        )
    }

    private companion object {
        /** No authentication in scope, so actors are roles rather than people. */
        const val STAFF = "front desk"
        const val PATIENT = "patient"
    }
}
