package no.dentline.booking.web

import no.dentline.booking.service.AvailabilityService
import no.dentline.booking.web.dto.ApiMapper
import no.dentline.booking.web.dto.AvailabilityResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/availability")
class AvailabilityController(private val availability: AvailabilityService, private val mapper: ApiMapper) {

    /** Omit `practitionerId` for the "first available" merge across the clinic. */
    @GetMapping
    fun availability(
        @RequestParam treatmentTypeId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
        @RequestParam(required = false) practitionerId: UUID?,
    ): AvailabilityResponse {
        val days = availability.availability(treatmentTypeId, from, to, practitionerId)
        return mapper.availability(days, availability.slotStep.toMinutes().toInt())
    }
}
