package no.dentline.booking.service

import no.dentline.booking.domain.SlotOffer
import no.dentline.booking.domain.WaitlistEntry
import no.dentline.booking.domain.WaitlistStatus
import no.dentline.booking.event.SlotOfferSnapshot
import no.dentline.booking.event.WaitlistEntrySnapshot
import no.dentline.booking.event.WaitlistEvent
import no.dentline.booking.event.WaitlistEventType
import no.dentline.booking.repository.SlotOfferRepository
import no.dentline.booking.repository.WaitlistEntryRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** An entry together with its derived queue position and latest offer: what the API and events show. */
data class WaitlistView(val entry: WaitlistEntry, val position: Int?, val queueSize: Int, val latestOffer: SlotOffer?)

/** Derives queue positions. Position is never stored: it is the entry's rank by creation time within its treatment's queue. */
@Component
class WaitlistQueue(private val entries: WaitlistEntryRepository, private val offers: SlotOfferRepository) {

    fun view(entry: WaitlistEntry): WaitlistView {
        val queue = entries.findQueue(entry.treatmentType.id, IN_QUEUE)
        val position = queue.indexOfFirst { it.id == entry.id }.takeIf { it >= 0 }?.plus(1)
        return WaitlistView(entry, position, queue.size, offers.findLatestForEntry(entry.id))
    }

    fun event(type: WaitlistEventType, view: WaitlistView, occurredAt: Instant, appointmentId: UUID? = null) = WaitlistEvent(
        type = type,
        occurredAt = occurredAt,
        entry = WaitlistEntrySnapshot.of(view.entry, view.position, view.queueSize),
        offer = view.latestOffer?.let(SlotOfferSnapshot::of),
        appointmentId = appointmentId,
    )

    companion object {
        val IN_QUEUE: Set<WaitlistStatus> = WaitlistStatus.entries.filter { it.isInQueue }.toSet()
        val ELIGIBLE: Set<WaitlistStatus> = WaitlistStatus.entries.filter { it.isQueueEligible }.toSet()
    }
}
