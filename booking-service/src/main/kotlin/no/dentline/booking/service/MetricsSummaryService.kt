package no.dentline.booking.service

import no.dentline.booking.domain.AppointmentStatus
import no.dentline.booking.domain.SlotOfferStatus
import no.dentline.booking.repository.AppointmentRepository
import no.dentline.booking.repository.SlotOfferRepository
import no.dentline.booking.repository.WaitlistEntryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The four stat cards. Values are computed from the database; Prometheus metrics are a separate, operational view. */
data class MetricsSummary(
    val bookingsToday: Counted,
    val cancellationsThisWeek: Cancellations,
    val noShowRate: Rate,
    val waitlistAvgWaitDays: WaitlistWait,
) {
    data class Counted(val value: Long, val delta: Long)
    data class Cancellations(val value: Long, val delta: Long, val refilledFromWaitlist: Long)
    /** Fractions (0.038 = 3.8 %). */
    data class Rate(val value: Double, val delta: Double)
    data class WaitlistWait(val value: Double, val delta: Double, val queued: Long)
}

@Service
class MetricsSummaryService(
    private val appointments: AppointmentRepository,
    private val offers: SlotOfferRepository,
    private val waitlist: WaitlistEntryRepository,
    private val clinicTime: ClinicTime,
) {
    @Transactional(readOnly = true)
    fun summary(): MetricsSummary {
        val today = clinicTime.today()
        val now = clinicTime.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        val bookingsToday = countStarting(today)
        val bookingsSameDayLastWeek = countStarting(today.minusWeeks(1))

        val cancelledThisWeek = countCancelled(monday, monday.plusWeeks(1))
        val cancelledLastWeek = countCancelled(monday.minusWeeks(1), monday)
        val refilled = offers.findByStatusRespondedBetween(SlotOfferStatus.ACCEPTED, clinicTime.startOfDay(monday), clinicTime.startOfDay(monday.plusWeeks(1))).size.toLong()

        val noShowNow = noShowRate(today.minusDays(30), today.plusDays(1))
        val noShowBefore = noShowRate(today.minusDays(60), today.minusDays(30))

        val waitNow = averageWaitDays(now - THIRTY_DAYS, now)
        val waitBefore = averageWaitDays(now - THIRTY_DAYS - THIRTY_DAYS, now - THIRTY_DAYS)

        return MetricsSummary(
            bookingsToday = MetricsSummary.Counted(bookingsToday, bookingsToday - bookingsSameDayLastWeek),
            cancellationsThisWeek = MetricsSummary.Cancellations(cancelledThisWeek, cancelledThisWeek - cancelledLastWeek, refilled),
            noShowRate = MetricsSummary.Rate(noShowNow, noShowNow - noShowBefore),
            waitlistAvgWaitDays = MetricsSummary.WaitlistWait(waitNow, waitNow - waitBefore, waitlist.countByStatusIn(WaitlistQueue.IN_QUEUE)),
        )
    }

    private fun countStarting(date: LocalDate): Long =
        appointments.countStartingBetween(clinicTime.startOfDay(date), clinicTime.startOfDay(date.plusDays(1)))

    private fun countCancelled(from: LocalDate, to: LocalDate): Long =
        appointments.countByStatusUpdatedBetween(AppointmentStatus.CANCELLED, clinicTime.startOfDay(from), clinicTime.startOfDay(to))

    /** NO_SHOW / (COMPLETED + NO_SHOW) over appointments starting in [from, to); 0 when there were none. */
    private fun noShowRate(from: LocalDate, to: LocalDate): Double {
        val start = clinicTime.startOfDay(from)
        val end = clinicTime.startOfDay(to)
        val noShows = appointments.countByStatusStartingBetween(AppointmentStatus.NO_SHOW, start, end)
        val completed = appointments.countByStatusStartingBetween(AppointmentStatus.COMPLETED, start, end)
        val attended = noShows + completed
        return if (attended == 0L) 0.0 else noShows.toDouble() / attended
    }

    /** Mean days from joining the waitlist to accepting an offer, over offers accepted in [from, to). */
    private fun averageWaitDays(from: Instant, to: Instant): Double {
        val accepted = offers.findByStatusRespondedBetween(SlotOfferStatus.ACCEPTED, from, to)
        if (accepted.isEmpty()) return 0.0
        val totalSeconds = accepted.sumOf { Duration.between(it.waitlistEntry.createdAt, it.respondedAt).seconds }
        return totalSeconds.toDouble() / accepted.size / SECONDS_PER_DAY
    }

    private companion object {
        val THIRTY_DAYS: Duration = Duration.ofDays(30)
        const val SECONDS_PER_DAY = 86_400.0
    }
}
