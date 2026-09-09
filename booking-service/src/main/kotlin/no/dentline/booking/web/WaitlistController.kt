package no.dentline.booking.web

import jakarta.validation.Valid
import no.dentline.booking.service.JoinWaitlist
import no.dentline.booking.service.WaitlistService
import no.dentline.booking.web.dto.JoinWaitlistRequest
import no.dentline.booking.web.dto.OfferAcceptedResponse
import no.dentline.booking.web.dto.WaitlistEntryResponse
import no.dentline.booking.web.dto.WaitlistMapper
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Patient side of the waitlist. The client polls `GET /{id}` while on the queue screen to pick up an offer. */
@RestController
@RequestMapping("/api/waitlist")
class WaitlistController(private val service: WaitlistService, private val mapper: WaitlistMapper) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun join(@Valid @RequestBody request: JoinWaitlistRequest): WaitlistEntryResponse {
        val command = JoinWaitlist(
            treatmentTypeId = request.treatmentTypeId!!,
            practitionerId = request.practitionerId,
            preferredWindows = request.preferredWindows!!,
            patient = request.patient!!.toPatient(),
        )
        return mapper.entry(service.join(command))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): WaitlistEntryResponse = mapper.entry(service.get(id))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leave(@PathVariable id: UUID) = service.leave(id)

    @PostMapping("/{id}/offer/accept")
    fun accept(@PathVariable id: UUID): OfferAcceptedResponse = mapper.accepted(service.accept(id))

    @PostMapping("/{id}/offer/decline")
    fun decline(@PathVariable id: UUID): WaitlistEntryResponse = mapper.entry(service.decline(id))
}

/** Front-desk side: the queue table and manual offers. */
@RestController
@RequestMapping("/api/staff/waitlist")
class StaffWaitlistController(private val service: WaitlistService, private val mapper: WaitlistMapper) {

    @GetMapping
    fun list(): List<WaitlistEntryResponse> = service.staffList().map(mapper::entry)

    @PostMapping("/{id}/offer")
    fun sendOffer(@PathVariable id: UUID): WaitlistEntryResponse = mapper.entry(service.sendOffer(id))

    @PostMapping("/{id}/offer/withdraw")
    fun withdraw(@PathVariable id: UUID): WaitlistEntryResponse = mapper.entry(service.withdrawOffer(id))
}
