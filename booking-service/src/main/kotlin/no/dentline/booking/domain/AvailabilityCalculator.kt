package no.dentline.booking.domain

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.SortedSet
import java.util.TreeSet
import java.util.UUID

/** A working-hours window within one day, e.g. 08:00–11:30. */
data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    init {
        require(start.isBefore(end)) { "Window must start before it ends: $start–$end" }
    }
}

/** Something that occupies a calendar: a blocking appointment or a pending slot offer. */
data class BusyInterval(val start: LocalDateTime, val end: LocalDateTime) {
    init {
        require(start.isBefore(end)) { "Busy interval must start before it ends: $start–$end" }
    }

    fun overlaps(candidateStart: LocalDateTime, candidateEnd: LocalDateTime): Boolean =
        candidateStart.isBefore(end) && candidateEnd.isAfter(start)
}

data class AvailableSlot(val start: LocalDateTime, val practitionerId: UUID)

/**
 * Availability is computed, never stored (see README). All times are clinic-local; the caller
 * converts `Instant`s using the clinic zone.
 *
 * ```
 * for each working window on the day:
 *   for t = window.start; t + duration <= window.end; t += slotStep:
 *     emit t unless it is before `notBefore` or overlaps any busy interval
 * ```
 */
class AvailabilityCalculator(private val slotStep: Duration = Duration.ofMinutes(15)) {
    init {
        require(!slotStep.isZero && !slotStep.isNegative) { "Slot step must be positive" }
    }

    fun slots(
        date: LocalDate,
        workingHours: List<TimeWindow>,
        treatmentDuration: Duration,
        busy: List<BusyInterval> = emptyList(),
        notBefore: LocalDateTime? = null,
    ): List<LocalDateTime> {
        require(!treatmentDuration.isZero && !treatmentDuration.isNegative) { "Treatment duration must be positive" }
        val result: SortedSet<LocalDateTime> = TreeSet()
        for (window in workingHours) {
            val windowEnd = date.atTime(window.end)
            var start = date.atTime(window.start)
            var end = start + treatmentDuration
            while (!end.isAfter(windowEnd)) {
                val tooEarly = notBefore != null && start.isBefore(notBefore)
                if (!tooEarly && busy.none { it.overlaps(start, end) }) result += start
                start += slotStep
                end = start + treatmentDuration
            }
        }
        return result.toList()
    }

    /**
     * "First available": merge several practitioners' slots, keeping one entry per start time.
     * When two practitioners are free at the same time the first in [perPractitioner] wins.
     */
    fun firstAvailable(perPractitioner: List<Pair<UUID, List<LocalDateTime>>>): List<AvailableSlot> =
        perPractitioner
            .flatMap { (practitionerId, slots) -> slots.map { AvailableSlot(it, practitionerId) } }
            .distinctBy { it.start }
            .sortedBy { it.start }
}
