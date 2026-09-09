package no.dentline.booking.web.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import no.dentline.booking.domain.Patient
import no.dentline.booking.event.CancelledBy
import java.time.Instant
import java.util.UUID

/** Validation messages are the exact copy the booking form shows under each field. */
data class PatientRequest(
    @field:NotBlank(message = "Please enter your full name")
    @field:Size(min = 2, message = "Please enter your full name")
    val name: String?,
    @field:NotBlank(message = "Enter a phone number we can text")
    @field:Pattern(regexp = "(?:\\D*\\d){8,}\\D*", message = "Enter a phone number we can text")
    val phone: String?,
    @field:NotBlank(message = "Enter a valid email address")
    @field:Pattern(regexp = "^\\S+@\\S+\\.\\S+$", message = "Enter a valid email address")
    val email: String?,
) {
    fun toPatient() = Patient(name = name!!.trim(), phone = phone!!.trim(), email = email!!.trim())
}

data class BookAppointmentRequest(
    @field:NotNull val treatmentTypeId: UUID?,
    @field:NotNull val practitionerId: UUID?,
    @field:NotNull val startTime: Instant?,
    @field:NotNull @field:Valid val patient: PatientRequest?,
)

data class RescheduleRequest(
    @field:NotNull val startTime: Instant?,
    val practitionerId: UUID? = null,
)

data class CancelRequest(val by: CancelledBy = CancelledBy.PATIENT)
