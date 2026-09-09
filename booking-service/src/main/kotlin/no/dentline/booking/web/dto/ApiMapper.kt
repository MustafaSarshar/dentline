package no.dentline.booking.web.dto

import no.dentline.booking.domain.Appointment
import no.dentline.booking.domain.AvailableSlot
import no.dentline.booking.domain.Practitioner
import no.dentline.booking.domain.TimeWindow
import no.dentline.booking.domain.TreatmentType
import no.dentline.booking.service.AvailabilityService
import no.dentline.booking.service.ClinicTime
import no.dentline.booking.service.ScheduleService
import org.springframework.stereotype.Component
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Entity → response mapping. Kept in one place so time-zone handling never leaks into controllers. */
@Component
class ApiMapper(private val clinicTime: ClinicTime) {
    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun treatmentType(t: TreatmentType) = TreatmentTypeResponse(t.id, t.code, t.name, t.durationMinutes, t.priceNok)

    fun practitioner(p: Practitioner) = PractitionerResponse(
        id = p.id,
        name = p.name,
        title = p.title,
        workingHours = p.workingHours.map { WorkingHoursResponse(it.dayOfWeek.value, it.startTime.hhmm(), it.endTime.hhmm()) },
    )

    fun appointment(a: Appointment, includeHistory: Boolean = false) = AppointmentResponse(
        id = a.id,
        reference = a.reference,
        status = a.status,
        patient = PatientResponse(a.patient.name, a.patient.phone, a.patient.email),
        practitioner = PractitionerRefResponse(a.practitioner.id, a.practitioner.name, a.practitioner.title),
        treatmentType = treatmentType(a.treatmentType),
        startTime = clinicTime.toOffset(a.startTime),
        endTime = clinicTime.toOffset(a.endTime),
        allowedActions = a.allowedActions.map { it.apiName },
        createdAt = clinicTime.toOffset(a.createdAt),
        updatedAt = clinicTime.toOffset(a.updatedAt),
        history = if (includeHistory) a.history.map { HistoryEntryResponse(clinicTime.toOffset(it.occurredAt), it.type, it.description) } else null,
    )

    fun availability(days: List<AvailabilityService.DayAvailability>, slotStepMinutes: Int) = AvailabilityResponse(
        slotStepMinutes = slotStepMinutes,
        days = days.map { DayAvailabilityResponse(it.date, it.closed, it.slots.map(::slot)) },
    )

    fun daySchedule(day: ScheduleService.DaySchedule) = DayScheduleResponse(
        date = day.date,
        clinicOpen = (day.clinicOpen ?: DEFAULT_OPEN).let(::timeRange),
        practitioners = day.practitioners.map {
            PractitionerDayResponse(
                id = it.practitioner.id,
                name = it.practitioner.name,
                title = it.practitioner.title,
                workingHours = it.workingHours.map(::timeRange),
                appointments = it.appointments.map { a -> appointment(a) },
            )
        },
    )

    fun weekSchedule(days: List<ScheduleService.WeekDay>) = WeekScheduleResponse(
        days = days.map { WeekDayResponse(it.date, it.appointments.map { a -> appointment(a) }, it.bookedCount, it.noShowCount, it.openSlotCount) },
    )

    private fun slot(s: AvailableSlot) = SlotResponse(clinicTime.toOffset(clinicTime.toInstant(s.start)), s.practitionerId)

    private fun timeRange(w: TimeWindow) = TimeRangeResponse(w.start.hhmm(), w.end.hhmm())

    private fun LocalTime.hhmm(): String = format(hhmm)

    private companion object {
        /** Grid bounds for a day nobody works, so the staff calendar still renders. */
        val DEFAULT_OPEN = TimeWindow(LocalTime.of(8, 0), LocalTime.of(18, 0))
    }
}
