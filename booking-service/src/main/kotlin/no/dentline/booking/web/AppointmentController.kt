package no.dentline.booking.web

import jakarta.validation.Valid
import no.dentline.booking.event.CancelledBy
import no.dentline.booking.metrics.BookingMetrics
import no.dentline.booking.service.AppointmentService
import no.dentline.booking.service.BookAppointment
import no.dentline.booking.web.dto.ApiMapper
import no.dentline.booking.web.dto.AppointmentResponse
import no.dentline.booking.web.dto.BookAppointmentRequest
import no.dentline.booking.web.dto.CancelRequest
import no.dentline.booking.web.dto.RescheduleRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Appointment resource. Transitions are verbs so the state machine is explicit in the API;
 * `cancel` and `reschedule` are patient-facing, the rest belong to the front desk.
 */
@RestController
@RequestMapping("/api/appointments")
class AppointmentController(
    private val service: AppointmentService,
    private val mapper: ApiMapper,
    private val metrics: BookingMetrics,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun book(@Valid @RequestBody request: BookAppointmentRequest): AppointmentResponse {
        val command = BookAppointment(
            treatmentTypeId = request.treatmentTypeId!!,
            practitionerId = request.practitionerId!!,
            startTime = request.startTime!!,
            patient = request.patient!!.toPatient(),
        )
        return metrics.timeBooking { mapper.appointment(service.book(command)) }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): AppointmentResponse = mapper.appointment(service.getWithHistory(id), includeHistory = true)

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: UUID, @RequestBody(required = false) request: CancelRequest?): AppointmentResponse =
        mapper.appointment(service.cancel(id, request?.by ?: CancelledBy.PATIENT))

    @PostMapping("/{id}/reschedule")
    fun reschedule(@PathVariable id: UUID, @Valid @RequestBody request: RescheduleRequest): AppointmentResponse =
        mapper.appointment(service.reschedule(id, request.startTime!!, request.practitionerId))

    @PostMapping("/{id}/confirm")
    fun confirm(@PathVariable id: UUID): AppointmentResponse = mapper.appointment(service.confirm(id))

    @PostMapping("/{id}/complete")
    fun complete(@PathVariable id: UUID): AppointmentResponse = mapper.appointment(service.complete(id))

    @PostMapping("/{id}/no-show")
    fun noShow(@PathVariable id: UUID): AppointmentResponse = mapper.appointment(service.markNoShow(id))
}
