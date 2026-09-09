package no.dentline.booking.event

import no.dentline.booking.domain.PreferredWindow
import no.dentline.booking.domain.SlotOffer
import no.dentline.booking.domain.SlotOfferStatus
import no.dentline.booking.domain.WaitlistEntry
import no.dentline.booking.domain.WaitlistStatus
import java.time.Instant
import java.util.UUID

/** Types on the `waitlist-events` topic. */
enum class WaitlistEventType { ENTRY_CREATED, OFFER_SENT, OFFER_ACCEPTED, OFFER_DECLINED, OFFER_EXPIRED, OFFER_WITHDRAWN, ENTRY_REMOVED }

data class WaitlistEvent(
    val type: WaitlistEventType,
    val occurredAt: Instant,
    val entry: WaitlistEntrySnapshot,
    val offer: SlotOfferSnapshot? = null,
    /** For OFFER_ACCEPTED: the appointment that was created. */
    val appointmentId: UUID? = null,
    val eventId: UUID = UUID.randomUUID(),
)

data class WaitlistEntrySnapshot(
    val id: UUID,
    val status: WaitlistStatus,
    val patient: PatientSnapshot,
    val treatmentTypeId: UUID,
    val treatmentName: String,
    val durationMinutes: Int,
    val practitionerId: UUID?,
    val preferredWindows: Set<PreferredWindow>,
    val position: Int?,
    val queueSize: Int,
    val createdAt: Instant,
) {
    companion object {
        fun of(entry: WaitlistEntry, position: Int?, queueSize: Int) = WaitlistEntrySnapshot(
            id = entry.id,
            status = entry.status,
            patient = PatientSnapshot(entry.patient.name, entry.patient.phone, entry.patient.email),
            treatmentTypeId = entry.treatmentType.id,
            treatmentName = entry.treatmentType.name,
            durationMinutes = entry.treatmentType.durationMinutes,
            practitionerId = entry.practitioner?.id,
            preferredWindows = entry.preferredWindows,
            position = position,
            queueSize = queueSize,
            createdAt = entry.createdAt,
        )
    }
}

data class SlotOfferSnapshot(
    val id: UUID,
    val practitionerId: UUID,
    val practitionerName: String,
    val startTime: Instant,
    val endTime: Instant,
    val expiresAt: Instant,
    val status: SlotOfferStatus,
) {
    companion object {
        fun of(offer: SlotOffer) = SlotOfferSnapshot(
            id = offer.id,
            practitionerId = offer.practitioner.id,
            practitionerName = offer.practitioner.name,
            startTime = offer.startTime,
            endTime = offer.endTime,
            expiresAt = offer.expiresAt,
            status = offer.status,
        )
    }
}
