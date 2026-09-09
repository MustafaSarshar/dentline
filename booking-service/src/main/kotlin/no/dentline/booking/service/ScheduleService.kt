package no.dentline.booking.service

import no.dentline.booking.domain.Appointment
import no.dentline.booking.domain.AppointmentStatus
import no.dentline.booking.domain.Practitioner
import no.dentline.booking.domain.TimeWindow
import no.dentline.booking.repository.AppointmentRepository
import no.dentline.booking.repository.PractitionerRepository
import no.dentline.booking.repository.TreatmentTypeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Read models for the staff "Today" and "Week" views. */
@Service
class ScheduleService(
    private val practitioners: PractitionerRepository,
    private val appointments: AppointmentRepository,
    private val treatmentTypes: TreatmentTypeRepository,
    private val availability: AvailabilityService,
    private val clinicTime: ClinicTime,
) {
    data class DaySchedule(val date: LocalDate, val clinicOpen: TimeWindow?, val practitioners: List<PractitionerDay>)
    data class PractitionerDay(val practitioner: Practitioner, val workingHours: List<TimeWindow>, val appointments: List<Appointment>)
    data class WeekDay(
        val date: LocalDate,
        val appointments: List<Appointment>,
        val bookedCount: Int,
        val noShowCount: Int,
        val openSlotCount: Int,
    )

    @Transactional(readOnly = true)
    fun day(date: LocalDate): DaySchedule {
        val all = practitioners.findAllByOrderByName()
        val byPractitioner = appointmentsOn(date, date).groupBy { it.practitioner.id }
        val windows = all.flatMap { it.workingWindowsOn(date.dayOfWeek) }
        val clinicOpen = windows.takeIf { it.isNotEmpty() }?.let { TimeWindow(it.minOf { w -> w.start }, it.maxOf { w -> w.end }) }
        return DaySchedule(
            date = date,
            clinicOpen = clinicOpen,
            practitioners = all.map { PractitionerDay(it, it.workingWindowsOn(date.dayOfWeek), byPractitioner[it.id].orEmpty()) },
        )
    }

    @Transactional(readOnly = true)
    fun range(from: LocalDate, to: LocalDate): List<WeekDay> {
        require(!to.isBefore(from)) { "'to' must not be before 'from'" }
        require(ChronoUnit.DAYS.between(from, to) < MAX_RANGE_DAYS) { "The range may span at most $MAX_RANGE_DAYS days" }
        val byDate = appointmentsOn(from, to).groupBy { clinicTime.toLocal(it.startTime).toLocalDate() }
        // "Openings" means room for the shortest treatment somewhere in the clinic.
        val shortest = treatmentTypes.findAll().minOf { it.duration }
        val openings = availability.availabilityFor(practitioners.findAllByOrderByName(), shortest, from, to).associateBy { it.date }
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { date ->
            val onDay = byDate[date].orEmpty()
            WeekDay(
                date = date,
                appointments = onDay,
                bookedCount = onDay.count { it.status in BOOKED },
                noShowCount = onDay.count { it.status == AppointmentStatus.NO_SHOW },
                openSlotCount = openings.getValue(date).totalSlotCount,
            )
        }.toList()
    }

    private fun appointmentsOn(from: LocalDate, to: LocalDate): List<Appointment> =
        appointments.findAllStartingBetween(clinicTime.startOfDay(from), clinicTime.startOfDay(to.plusDays(1)))

    private companion object {
        const val MAX_RANGE_DAYS = 14
        val BOOKED = setOf(AppointmentStatus.REQUESTED, AppointmentStatus.CONFIRMED, AppointmentStatus.COMPLETED)
    }
}
