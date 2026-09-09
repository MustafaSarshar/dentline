package no.dentline.booking.service

import no.dentline.booking.config.DentlineProperties
import no.dentline.booking.domain.Appointment
import no.dentline.booking.domain.ConflictCode
import no.dentline.booking.domain.DomainConflictException
import no.dentline.booking.domain.NotFoundException
import no.dentline.booking.domain.Patient
import no.dentline.booking.domain.PreferredWindow
import no.dentline.booking.domain.WaitlistEntry
import no.dentline.booking.domain.WaitlistStatus
import no.dentline.booking.event.AppointmentEvent
import no.dentline.booking.event.AppointmentEventType
import no.dentline.booking.event.AppointmentSnapshot
import no.dentline.booking.event.WaitlistEventType
import no.dentline.booking.repository.AppointmentRepository
import no.dentline.booking.repository.PractitionerRepository
import no.dentline.booking.repository.SlotOfferRepository
import no.dentline.booking.repository.TreatmentTypeRepository
import no.dentline.booking.repository.WaitlistEntryRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

data class JoinWaitlist(
    val treatmentTypeId: UUID,
    val practitionerId: UUID?,
    val preferredWindows: Set<PreferredWindow>,
    val patient: Patient,
)

data class OfferAccepted(val view: WaitlistView, val appointment: Appointment)

@Service
class WaitlistService(
    private val entries: WaitlistEntryRepository,
    private val offers: SlotOfferRepository,
    private val practitioners: PractitionerRepository,
    private val treatmentTypes: TreatmentTypeRepository,
    private val appointments: AppointmentRepository,
    private val appointmentService: AppointmentService,
    private val availability: AvailabilityService,
    private val matcher: WaitlistMatcher,
    private val queue: WaitlistQueue,
    private val clinicTime: ClinicTime,
    private val properties: DentlineProperties,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun join(command: JoinWaitlist): WaitlistView {
        val treatment = treatmentTypes.findById(command.treatmentTypeId)
            .orElseThrow { NotFoundException("Treatment type", command.treatmentTypeId) }
        val practitioner = command.practitionerId?.let { id ->
            practitioners.findById(id).orElseThrow { NotFoundException("Practitioner", id) }
        }
        val now = clinicTime.now()
        val entry = entries.save(
            WaitlistEntry(
                patient = command.patient,
                treatmentType = treatment,
                practitioner = practitioner,
                preferredWindows = command.preferredWindows,
                createdAt = now,
            ),
        )
        val view = queue.view(entry)
        events.publishEvent(queue.event(WaitlistEventType.ENTRY_CREATED, view, now))
        return view
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): WaitlistView = queue.view(find(id))

    @Transactional
    fun leave(id: UUID) {
        val entry = lock(id)
        val now = clinicTime.now()
        offers.findPendingForEntry(entry.id)?.withdraw(now)
        entry.remove(now)
        events.publishEvent(queue.event(WaitlistEventType.ENTRY_REMOVED, queue.view(entry), now))
    }

    /**
     * Turns a pending offer into a CONFIRMED appointment. The practitioner is locked and the
     * window re-checked (ignoring this very offer, which is what holds it) so an accept can
     * never double-book even if a staff member booked the slot by hand in the meantime.
     */
    @Transactional
    fun accept(id: UUID): OfferAccepted {
        val entry = lock(id)
        val offer = offers.findPendingForEntry(entry.id) ?: throw noActiveOffer()
        val now = clinicTime.now()
        if (!offer.isActive(now)) throw noActiveOffer()

        val practitioner = practitioners.lockForUpdate(offer.practitioner.id) ?: throw NotFoundException("Practitioner", offer.practitioner.id)
        appointmentService.ensureBookable(practitioner, offer.startTime, offer.endTime, ignoreAppointmentId = null, ignoreOfferId = offer.id)

        val appointment = appointments.save(
            Appointment.fromAcceptedOffer(
                reference = appointmentService.nextReference(),
                practitioner = practitioner,
                treatmentType = entry.treatmentType,
                patient = entry.patient,
                startTime = offer.startTime,
                now = now,
            ),
        )
        offer.accept(now)
        entry.accept(appointment, now)

        events.publishEvent(AppointmentEvent(AppointmentEventType.BOOKED, now, AppointmentSnapshot.of(appointment)))
        val view = queue.view(entry)
        events.publishEvent(queue.event(WaitlistEventType.OFFER_ACCEPTED, view, now, appointmentId = appointment.id))
        return OfferAccepted(view, appointment)
    }

    /** Declining keeps the patient's place; the window is immediately offered to the next match. */
    @Transactional
    fun decline(id: UUID): WaitlistView {
        val entry = lock(id)
        val offer = offers.findPendingForEntry(entry.id) ?: throw noActiveOffer()
        val now = clinicTime.now()
        offer.decline(now)
        entry.decline(now)
        events.publishEvent(queue.event(WaitlistEventType.OFFER_DECLINED, queue.view(entry), now))
        matcher.offerFreedWindow(offer.practitioner.id, offer.startTime, offer.endTime)
        return queue.view(entry)
    }

    // --- staff ---

    @Transactional(readOnly = true)
    fun staffList(): List<WaitlistView> =
        entries.findForStaff(acceptedSince = clinicTime.now() - ACCEPTED_VISIBLE_FOR).map(queue::view)

    /**
     * Manual "Send offer": the design has no slot picker, so the server offers the earliest free
     * slot within the search horizon that matches the entry's treatment, practitioner and windows.
     */
    @Transactional
    fun sendOffer(id: UUID): WaitlistView {
        val entry = lock(id)
        if (entry.status == WaitlistStatus.OFFERED) {
            throw DomainConflictException(ConflictCode.OFFER_ALREADY_PENDING, "This patient already has a pending offer")
        }
        if (!entry.status.isQueueEligible) {
            throw DomainConflictException(ConflictCode.ILLEGAL_TRANSITION, "Waitlist entry in state ${entry.status} cannot receive an offer")
        }
        val today = clinicTime.today()
        val scope = entry.practitioner?.let(::listOf) ?: practitioners.findAllByOrderByName()
        val horizon = today.plusDays(properties.waitlist.manualOfferSearchDays.toLong())
        val slot = availability.availabilityFor(scope, entry.treatmentType.duration, today, horizon)
            .flatMap { it.slots }
            .firstOrNull { entry.wantsSlot(it.start, it.practitionerId, today) }
            ?: throw DomainConflictException(ConflictCode.NO_MATCHING_SLOT, "No free slot matches this patient's preferences in the next ${properties.waitlist.manualOfferSearchDays} days")

        val practitioner = practitioners.lockForUpdate(slot.practitionerId) ?: throw NotFoundException("Practitioner", slot.practitionerId)
        val start = clinicTime.toInstant(slot.start)
        val end = start + entry.treatmentType.duration
        appointmentService.ensureBookable(practitioner, start, end, ignoreAppointmentId = null)
        matcher.createOffer(entry, practitioner, start, end, clinicTime.now())
        return queue.view(entry)
    }

    @Transactional
    fun withdrawOffer(id: UUID): WaitlistView {
        val entry = lock(id)
        val offer = offers.findPendingForEntry(entry.id) ?: throw noActiveOffer()
        val now = clinicTime.now()
        offer.withdraw(now)
        entry.withdrawOffer(now)
        events.publishEvent(queue.event(WaitlistEventType.OFFER_WITHDRAWN, queue.view(entry), now))
        return queue.view(entry)
    }

    /** The minute sweep: expire overdue offers and pass each freed window on. Returns how many expired. */
    @Transactional
    fun expirePendingOffers(): Int {
        val now = clinicTime.now()
        val expired = offers.findExpired(now)
        for (offer in expired) {
            val entry = offer.waitlistEntry
            offer.expire(now)
            entry.expire(now)
            events.publishEvent(queue.event(WaitlistEventType.OFFER_EXPIRED, queue.view(entry), now))
            matcher.offerFreedWindow(offer.practitioner.id, offer.startTime, offer.endTime)
        }
        return expired.size
    }

    private fun find(id: UUID): WaitlistEntry = entries.findById(id).orElseThrow { NotFoundException("Waitlist entry", id) }

    private fun lock(id: UUID): WaitlistEntry = entries.lockForUpdate(id) ?: throw NotFoundException("Waitlist entry", id)

    private fun noActiveOffer() = DomainConflictException(ConflictCode.OFFER_NOT_ACTIVE, "There is no active offer for this waitlist entry")

    private companion object {
        val ACCEPTED_VISIBLE_FOR: Duration = Duration.ofHours(24)
    }
}
