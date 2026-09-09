package no.dentline.booking.service

import no.dentline.booking.config.DentlineProperties
import no.dentline.booking.domain.AvailabilityCalculator
import no.dentline.booking.domain.AvailableSlot
import no.dentline.booking.domain.BusyInterval
import no.dentline.booking.domain.NotFoundException
import no.dentline.booking.domain.Practitioner
import no.dentline.booking.repository.AppointmentRepository
import no.dentline.booking.repository.PractitionerRepository
import no.dentline.booking.repository.SlotOfferRepository
import no.dentline.booking.repository.TreatmentTypeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Computes availability on demand: working hours minus blocking appointments minus pending
 * slot offers. Nothing here is persisted (see README, "Why availability is computed").
 */
@Service
class AvailabilityService(
    private val practitioners: PractitionerRepository,
    private val treatmentTypes: TreatmentTypeRepository,
    private val appointments: AppointmentRepository,
    private val slotOffers: SlotOfferRepository,
    private val clinicTime: ClinicTime,
    private val properties: DentlineProperties,
) {
    private val calculator = AvailabilityCalculator(properties.availability.slotStep)

    val slotStep: Duration get() = properties.availability.slotStep

    data class DayAvailability(
        val date: LocalDate,
        /** No practitioner in scope works that day. `false` with no slots means "Full". */
        val closed: Boolean,
        /** Merged "first available" view: one entry per start time. */
        val slots: List<AvailableSlot>,
        /** Sum over practitioners before merging; used by the week view's opening count. */
        val totalSlotCount: Int,
    )

    @Transactional(readOnly = true)
    fun availability(treatmentTypeId: UUID, from: LocalDate, to: LocalDate, practitionerId: UUID?): List<DayAvailability> {
        require(!to.isBefore(from)) { "'to' must not be before 'from'" }
        require(ChronoUnit.DAYS.between(from, to) < properties.availability.maxRangeDays) {
            "The range may span at most ${properties.availability.maxRangeDays} days"
        }
        val treatment = treatmentTypes.findById(treatmentTypeId)
            .orElseThrow { NotFoundException("Treatment type", treatmentTypeId) }
        val scope = when (practitionerId) {
            null -> practitioners.findAllByOrderByName()
            else -> listOf(practitioners.findById(practitionerId).orElseThrow { NotFoundException("Practitioner", practitionerId) })
        }
        return availabilityFor(scope, treatment.duration, from, to)
    }

    /** Shared with the schedule view, which needs opening counts per day. */
    fun availabilityFor(scope: List<Practitioner>, treatmentDuration: Duration, from: LocalDate, to: LocalDate): List<DayAvailability> {
        val rangeStart = clinicTime.startOfDay(from)
        val rangeEnd = clinicTime.startOfDay(to.plusDays(1))
        val busyByPractitioner = scope.associate { it.id to busyIntervals(it.id, rangeStart, rangeEnd) }
        val notBefore = clinicTime.nowLocal()

        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .map { date ->
                val perPractitioner = scope.map { practitioner ->
                    practitioner.id to calculator.slots(
                        date = date,
                        workingHours = practitioner.workingWindowsOn(date.dayOfWeek),
                        treatmentDuration = treatmentDuration,
                        busy = busyByPractitioner.getValue(practitioner.id),
                        notBefore = notBefore,
                    )
                }
                DayAvailability(
                    date = date,
                    closed = scope.all { it.workingWindowsOn(date.dayOfWeek).isEmpty() },
                    slots = calculator.firstAvailable(perPractitioner),
                    totalSlotCount = perPractitioner.sumOf { (_, slots) -> slots.size },
                )
            }
            .toList()
    }

    private fun busyIntervals(practitionerId: UUID, from: Instant, to: Instant): List<BusyInterval> {
        val booked = appointments.findOverlapping(practitionerId, from, to)
            .map { BusyInterval(clinicTime.toLocal(it.startTime), clinicTime.toLocal(it.endTime)) }
        val held = slotOffers.findPendingOverlapping(practitionerId, from, to)
            .map { BusyInterval(clinicTime.toLocal(it.startTime), clinicTime.toLocal(it.endTime)) }
        return booked + held
    }
}
