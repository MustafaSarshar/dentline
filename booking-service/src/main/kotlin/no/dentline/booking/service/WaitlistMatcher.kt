package no.dentline.booking.service

import no.dentline.booking.config.DentlineProperties
import no.dentline.booking.domain.DomainConflictException
import no.dentline.booking.domain.Practitioner
import no.dentline.booking.domain.SlotOffer
import no.dentline.booking.domain.WaitlistEntry
import no.dentline.booking.event.WaitlistEventType
import no.dentline.booking.repository.PractitionerRepository
import no.dentline.booking.repository.SlotOfferRepository
import no.dentline.booking.repository.WaitlistEntryRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Turns a freed calendar window into a time-limited offer for the first waitlist entry it fits.
 *
 * Candidates are walked oldest-first across all treatments. An entry qualifies when its
 * treatment fits inside the window, the start satisfies one of its preferred windows and its
 * practitioner preference, and it has not already turned down this exact slot. The window is
 * re-checked under the practitioner lock before the offer is made, because a walk-in booking
 * may have taken it between the cancellation and this call.
 */
@Service
class WaitlistMatcher(
    private val entries: WaitlistEntryRepository,
    private val offers: SlotOfferRepository,
    private val practitioners: PractitionerRepository,
    private val appointmentService: AppointmentService,
    private val queue: WaitlistQueue,
    private val clinicTime: ClinicTime,
    private val properties: DentlineProperties,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return the offer made, or null when nobody matched or the window is no longer free. */
    @Transactional
    fun offerFreedWindow(practitionerId: UUID, windowStart: Instant, windowEnd: Instant): SlotOffer? {
        val now = clinicTime.now()
        if (!windowStart.isAfter(now)) return null
        val practitioner = practitioners.lockForUpdate(practitionerId) ?: return null
        val today = clinicTime.today()
        val localStart = clinicTime.toLocal(windowStart)

        for (entry in entries.findByStatusInOrderByCreatedAt(WaitlistQueue.ELIGIBLE)) {
            val end = windowStart + entry.treatmentType.duration
            if (end.isAfter(windowEnd)) continue
            if (!entry.wantsSlot(localStart, practitionerId, today)) continue
            if (offers.wasAlreadyOffered(entry.id, practitionerId, windowStart)) continue

            try {
                appointmentService.ensureBookable(practitioner, windowStart, end, ignoreAppointmentId = null)
            } catch (e: DomainConflictException) {
                log.info("Window {}–{} with {} is no longer free ({}); no offer made", windowStart, end, practitioner.name, e.code)
                return null
            }
            return createOffer(entry, practitioner, windowStart, end, now)
        }
        log.info("No waitlist entry matches {}–{} with {}", windowStart, windowEnd, practitioner.name)
        return null
    }

    /** Creates the hold and publishes OFFER_SENT. Caller must hold the practitioner lock and have verified the window. */
    fun createOffer(entry: WaitlistEntry, practitioner: Practitioner, start: Instant, end: Instant, now: Instant): SlotOffer {
        val offer = SlotOffer(
            waitlistEntry = entry,
            practitioner = practitioner,
            startTime = start,
            endTime = end,
            expiresAt = now + properties.waitlist.offerHold,
            createdAt = now,
        )
        entry.markOffered(now)
        // save() merges an entity with an assigned id and returns the managed copy; always use that copy.
        val saved = offers.save(offer)
        events.publishEvent(queue.event(WaitlistEventType.OFFER_SENT, queue.view(entry), now))
        log.info("Offered {}–{} with {} to waitlist entry {} (expires {})", start, end, practitioner.name, entry.id, saved.expiresAt)
        return saved
    }
}
