package no.dentline.booking.jobs

import no.dentline.booking.service.ClinicTime
import no.dentline.booking.service.RecallService
import no.dentline.booking.service.ReminderService
import no.dentline.booking.service.WaitlistService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The three clocks of the clinic. Each job is a thin wrapper; the logic (and the tests) live in
 * the services. Runs on a single instance; with several replicas these would need a lock
 * (e.g. ShedLock) — see README, "Next steps for production".
 */
@Component
class ScheduledJobs(
    private val reminders: ReminderService,
    private val recalls: RecallService,
    private val waitlist: WaitlistService,
    private val clinicTime: ClinicTime,
) {
    /** Every evening: remind everyone with a confirmed appointment tomorrow. */
    @Scheduled(cron = "0 0 18 * * *", zone = "\${dentline.timezone}")
    fun remindTomorrowsPatients() {
        reminders.sendRemindersFor(clinicTime.today().plusDays(1))
    }

    /** Every morning at 06:00: six-month check-up recalls. */
    @Scheduled(cron = "0 0 6 * * *", zone = "\${dentline.timezone}")
    fun sendRecalls() {
        recalls.sendDueRecalls()
    }

    /** Every minute: expire overdue slot offers and pass the freed windows on. */
    @Scheduled(cron = "0 * * * * *", zone = "\${dentline.timezone}")
    fun expireSlotOffers() {
        waitlist.expirePendingOffers()
    }
}
