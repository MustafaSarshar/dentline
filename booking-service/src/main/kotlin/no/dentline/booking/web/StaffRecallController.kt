package no.dentline.booking.web

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import no.dentline.booking.service.ClinicTime
import no.dentline.booking.service.RecallRow
import no.dentline.booking.service.RecallService
import no.dentline.booking.web.dto.PatientResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.OffsetDateTime

data class RecallResponse(
    val patient: PatientResponse,
    val practitionerName: String,
    val lastVisitDate: LocalDate,
    val dueDate: LocalDate,
    val recallSentAt: OffsetDateTime?,
)

data class SendRecallRequest(@field:NotBlank val email: String?)

/** Staff "Recalls" view: who is due for a six-month check-up, and "Send recall" / "Resend". */
@RestController
@RequestMapping("/api/staff/recalls")
class StaffRecallController(private val recalls: RecallService, private val clinicTime: ClinicTime) {

    @GetMapping
    fun list(): List<RecallResponse> = recalls.dueRecalls().map(::toResponse)

    @PostMapping("/send")
    fun send(@Valid @RequestBody request: SendRecallRequest): RecallResponse = toResponse(recalls.send(request.email!!))

    private fun toResponse(row: RecallRow) = RecallResponse(
        patient = PatientResponse(row.patient.name, row.patient.phone, row.patient.email),
        practitionerName = row.practitionerName,
        lastVisitDate = row.lastVisitDate,
        dueDate = row.dueDate,
        recallSentAt = row.recallSentAt?.let(clinicTime::toOffset),
    )
}
