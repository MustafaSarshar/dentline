package no.dentline.booking.service

import no.dentline.booking.event.AppointmentEvent
import no.dentline.booking.event.AppointmentEventType
import no.dentline.booking.event.AppointmentSnapshot
import no.dentline.booking.repository.AppointmentRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** "You'll receive a reminder the day before." */
@Service
class ReminderService(
    private val appointments: AppointmentRepository,
    private val clinicTime: ClinicTime,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Publishes REMINDER_DUE for every confirmed appointment on [date] that has not been reminded,
     * and marks it so a re-run (or a second instance) cannot send twice. Returns how many were sent.
     */
    @Transactional
    fun sendRemindersFor(date: LocalDate): Int {
        val now = clinicTime.now()
        val due = appointments.findConfirmedNeedingReminder(clinicTime.startOfDay(date), clinicTime.startOfDay(date.plusDays(1)))
        for (appointment in due) {
            appointment.markReminderSent(now)
            events.publishEvent(AppointmentEvent(AppointmentEventType.REMINDER_DUE, now, AppointmentSnapshot.of(appointment)))
        }
        log.info("Reminder job for {}: {} reminder(s) published", date, due.size)
        return due.size
    }
}
