package no.dentline.booking.web.dto

import no.dentline.booking.domain.AppointmentStatus
import no.dentline.booking.domain.HistoryType
import no.dentline.booking.domain.PractitionerTitle
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// Shapes match docs/02-api-contract.md. Instants are rendered as OffsetDateTime in the clinic zone.

data class ConfigResponse(
    val timezone: String,
    val slotStepMinutes: Int,
    val offerHoldMinutes: Int,
    val clinic: ClinicResponse,
)

data class ClinicResponse(val name: String, val addressLine1: String, val addressLine2: String)

data class TreatmentTypeResponse(
    val id: UUID,
    val code: String,
    val name: String,
    val durationMinutes: Int,
    val priceNok: BigDecimal,
)

data class WorkingHoursResponse(val dayOfWeek: Int, val startTime: String, val endTime: String)

data class TimeRangeResponse(val startTime: String, val endTime: String)

data class PractitionerResponse(
    val id: UUID,
    val name: String,
    val title: PractitionerTitle,
    val workingHours: List<WorkingHoursResponse>,
)

data class PractitionerRefResponse(val id: UUID, val name: String, val title: PractitionerTitle)

data class PatientResponse(val name: String, val phone: String, val email: String)

data class HistoryEntryResponse(val at: OffsetDateTime, val type: HistoryType, val description: String)

data class AppointmentResponse(
    val id: UUID,
    val reference: String,
    val status: AppointmentStatus,
    val patient: PatientResponse,
    val practitioner: PractitionerRefResponse,
    val treatmentType: TreatmentTypeResponse,
    val startTime: OffsetDateTime,
    val endTime: OffsetDateTime,
    val allowedActions: List<String>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val history: List<HistoryEntryResponse>? = null,
)

data class SlotResponse(val startTime: OffsetDateTime, val practitionerId: UUID)

data class DayAvailabilityResponse(val date: LocalDate, val closed: Boolean, val slots: List<SlotResponse>)

data class AvailabilityResponse(val slotStepMinutes: Int, val days: List<DayAvailabilityResponse>)

data class PractitionerDayResponse(
    val id: UUID,
    val name: String,
    val title: PractitionerTitle,
    val workingHours: List<TimeRangeResponse>,
    val appointments: List<AppointmentResponse>,
)

data class DayScheduleResponse(
    val date: LocalDate,
    val clinicOpen: TimeRangeResponse,
    val practitioners: List<PractitionerDayResponse>,
)

data class WeekDayResponse(
    val date: LocalDate,
    val appointments: List<AppointmentResponse>,
    val bookedCount: Int,
    val noShowCount: Int,
    val openSlotCount: Int,
)

data class WeekScheduleResponse(val days: List<WeekDayResponse>)
