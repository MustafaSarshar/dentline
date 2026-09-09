package no.dentline.booking.web.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import no.dentline.booking.domain.PreferredWindow
import no.dentline.booking.domain.SlotOffer
import no.dentline.booking.domain.SlotOfferStatus
import no.dentline.booking.domain.WaitlistStatus
import no.dentline.booking.service.ClinicTime
import no.dentline.booking.service.OfferAccepted
import no.dentline.booking.service.WaitlistView
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

data class JoinWaitlistRequest(
    @field:NotNull val treatmentTypeId: UUID?,
    val practitionerId: UUID? = null,
    @field:NotEmpty(message = "Choose at least one time window") val preferredWindows: Set<PreferredWindow>?,
    @field:NotNull @field:Valid val patient: PatientRequest?,
)

data class SlotOfferResponse(
    val id: UUID,
    val startTime: OffsetDateTime,
    val endTime: OffsetDateTime,
    val practitioner: PractitionerRefResponse,
    val expiresAt: OffsetDateTime,
    val status: SlotOfferStatus,
    val respondedAt: OffsetDateTime?,
)

data class WaitlistEntryResponse(
    val id: UUID,
    val status: WaitlistStatus,
    val patient: PatientResponse,
    val treatmentType: TreatmentTypeResponse,
    val practitionerId: UUID?,
    val preferredWindows: Set<PreferredWindow>,
    val position: Int?,
    val queueSize: Int,
    val createdAt: OffsetDateTime,
    /** The latest offer: PENDING while OFFERED, otherwise its final outcome (drives "Declined 11:04"). */
    val offer: SlotOfferResponse?,
    val appointmentId: UUID?,
)

data class OfferAcceptedResponse(val entry: WaitlistEntryResponse, val appointment: AppointmentResponse)

@Component
class WaitlistMapper(private val clinicTime: ClinicTime, private val api: ApiMapper) {

    fun entry(view: WaitlistView): WaitlistEntryResponse {
        val e = view.entry
        return WaitlistEntryResponse(
            id = e.id,
            status = e.status,
            patient = PatientResponse(e.patient.name, e.patient.phone, e.patient.email),
            treatmentType = api.treatmentType(e.treatmentType),
            practitionerId = e.practitioner?.id,
            preferredWindows = e.preferredWindows,
            position = view.position,
            queueSize = view.queueSize,
            createdAt = clinicTime.toOffset(e.createdAt),
            offer = view.latestOffer?.let(::offer),
            appointmentId = e.appointment?.id,
        )
    }

    fun accepted(result: OfferAccepted) = OfferAcceptedResponse(entry(result.view), api.appointment(result.appointment))

    private fun offer(o: SlotOffer) = SlotOfferResponse(
        id = o.id,
        startTime = clinicTime.toOffset(o.startTime),
        endTime = clinicTime.toOffset(o.endTime),
        practitioner = PractitionerRefResponse(o.practitioner.id, o.practitioner.name, o.practitioner.title),
        expiresAt = clinicTime.toOffset(o.expiresAt),
        status = o.status,
        respondedAt = o.respondedAt?.let(clinicTime::toOffset),
    )
}
