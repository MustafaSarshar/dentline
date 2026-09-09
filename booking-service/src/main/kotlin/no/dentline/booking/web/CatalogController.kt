package no.dentline.booking.web

import no.dentline.booking.config.DentlineProperties
import no.dentline.booking.repository.PractitionerRepository
import no.dentline.booking.repository.TreatmentTypeRepository
import no.dentline.booking.web.dto.ApiMapper
import no.dentline.booking.web.dto.ClinicResponse
import no.dentline.booking.web.dto.ConfigResponse
import no.dentline.booking.web.dto.PractitionerResponse
import no.dentline.booking.web.dto.TreatmentTypeResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Reference data the patient flow loads first: config, treatments, practitioners. */
@RestController
@RequestMapping("/api")
class CatalogController(
    private val properties: DentlineProperties,
    private val treatmentTypes: TreatmentTypeRepository,
    private val practitioners: PractitionerRepository,
    private val mapper: ApiMapper,
) {
    @GetMapping("/config")
    fun config() = ConfigResponse(
        timezone = properties.timezone,
        slotStepMinutes = properties.availability.slotStepMinutes,
        offerHoldMinutes = properties.waitlist.offerHoldMinutes,
        clinic = ClinicResponse(properties.clinic.name, properties.clinic.addressLine1, properties.clinic.addressLine2),
    )

    @GetMapping("/treatment-types")
    fun treatmentTypes(): List<TreatmentTypeResponse> =
        treatmentTypes.findAllByOrderByDurationMinutesAscNameAsc().map(mapper::treatmentType)

    @GetMapping("/practitioners")
    fun practitioners(): List<PractitionerResponse> = practitioners.findAllByOrderByName().map(mapper::practitioner)
}
