package no.dentline.booking

import no.dentline.booking.domain.Appointment
import no.dentline.booking.domain.AppointmentStatus
import no.dentline.booking.domain.Patient
import no.dentline.booking.event.AppointmentEventType
import no.dentline.booking.event.WaitlistEventType
import no.dentline.booking.repository.AppointmentRepository
import no.dentline.booking.repository.PractitionerRepository
import no.dentline.booking.repository.TreatmentTypeRepository
import no.dentline.booking.service.RecallService
import no.dentline.booking.service.ReminderService
import no.dentline.booking.service.WaitlistService
import no.dentline.booking.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * The scheduled jobs, exercised through the services they wrap so the tests do not have to wait
 * for cron. Time-dependent inputs are set up directly in the database.
 */
class ScheduledJobsIT : IntegrationTest() {
    @Autowired lateinit var reminders: ReminderService
    @Autowired lateinit var recalls: RecallService
    @Autowired lateinit var waitlist: WaitlistService
    @Autowired lateinit var appointments: AppointmentRepository
    @Autowired lateinit var practitioners: PractitionerRepository
    @Autowired lateinit var treatmentTypes: TreatmentTypeRepository

    @Test
    fun `the reminder job reminds confirmed appointments once, and skips unconfirmed ones`() {
        val monday = nextMonday()
        val confirmed = book(monday.at("08:00"))["id"].asText().also { post("/api/appointments/$it/confirm") }
        val stillRequested = book(monday.at("09:00"))["id"].asText()
        book(monday.plusDays(1).at("08:00"))["id"].asText().also { post("/api/appointments/$it/confirm") } // another day
        events.clear()

        val sent = reminders.sendRemindersFor(monday)

        assertThat(sent).isEqualTo(1)
        val reminder = events.ofType(AppointmentEventType.REMINDER_DUE).single()
        assertThat(reminder.appointment.id.toString()).isEqualTo(confirmed)
        assertThat(reminder.appointment.id.toString()).isNotEqualTo(stillRequested)

        assertThat(reminders.sendRemindersFor(monday)).describedAs("a second run sends nothing").isZero()
    }

    @Test
    fun `recalls list patients whose last check-up is nearly six months old, until they book again`() {
        val overdue = completedCheckUp("Hedda Moen", "hedda.moen@example.com", monthsAgo = 7)
        val dueSoon = completedCheckUp("Vetle Aas", "vetle.aas@example.com", monthsAgo = 5)
        completedCheckUp("Fresh Fossum", "fresh@example.com", monthsAgo = 2)
        val rebooked = completedCheckUp("Bjørn Tvedt", "bjorn.tvedt@example.com", monthsAgo = 8)
        post("/api/appointments", bookingRequest(startTime = nextMonday().at("12:15"), name = "Bjørn Tvedt", email = rebooked.email))

        val rows = get("/api/staff/recalls").body!!

        assertThat(rows.map { it["patient"]["email"].asText() }).containsExactly(overdue.email, dueSoon.email)
        val hedda = rows[0]
        assertThat(LocalDate.parse(hedda["dueDate"].asText())).isEqualTo(LocalDate.parse(hedda["lastVisitDate"].asText()).plusMonths(6))
        assertThat(hedda["practitionerName"].asText()).isEqualTo("Dr. Astrid Nordvik")
        assertThat(hedda["recallSentAt"].isNull).isTrue()
    }

    @Test
    fun `the recall job sends overdue recalls once a month, and the front desk can resend by hand`() {
        val hedda = completedCheckUp("Hedda Moen", "hedda.moen@example.com", monthsAgo = 7)
        completedCheckUp("Vetle Aas", "vetle.aas@example.com", monthsAgo = 5) // listed, but not yet due

        assertThat(recalls.sendDueRecalls()).isEqualTo(1)
        val event = events.recalls.single()
        assertThat(event.patient.email).isEqualTo(hedda.email)
        assertThat(event.dueDate).isEqualTo(event.lastVisitDate.plusMonths(6))
        assertThat(get("/api/staff/recalls").body!![0]["recallSentAt"].isNull).isFalse()

        assertThat(recalls.sendDueRecalls()).describedAs("already reminded this month").isZero()

        val resent = post("/api/staff/recalls/send", mapOf("email" to hedda.email))
        assertThat(resent.statusCode.value()).isEqualTo(200)
        assertThat(events.recalls).hasSize(2)
        assertThat(post("/api/staff/recalls/send", mapOf("email" to "nobody@example.com")).statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `the expiry sweep expires overdue offers and passes the window to the next in line`() {
        val monday = nextMonday()
        val appointment = book(monday.at("08:00"))
        val slow = joinWaitlist(name = "Slow Solberg", email = "slow@example.com")
        val next = joinWaitlist(name = "Next Nilsen", email = "next@example.com")
        post("/api/appointments/${appointment["id"].asText()}/cancel")
        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            assertThat(waitlistEntry(slow["id"].asText())["status"].asText()).isEqualTo("OFFERED")
        }

        assertThat(waitlist.expirePendingOffers()).describedAs("nothing has expired yet").isZero()
        timePasses()

        assertThat(waitlist.expirePendingOffers()).isEqualTo(1)
        val expired = waitlistEntry(slow["id"].asText())
        assertThat(expired["status"].asText()).isEqualTo("EXPIRED")
        assertThat(expired["position"].asInt()).describedAs("keeps their place").isEqualTo(1)
        assertThat(expired["offer"]["status"].asText()).isEqualTo("EXPIRED")
        assertThat(events.waitlist(WaitlistEventType.OFFER_EXPIRED)).hasSize(1)
        val offeredNext = waitlistEntry(next["id"].asText())
        assertThat(offeredNext["status"].asText()).isEqualTo("OFFERED")
        assertThat(offeredNext["offer"]["startTime"].localTime()).isEqualTo("08:00")
    }

    /** Pushes every pending offer's deadline into the past. */
    private fun timePasses() {
        jdbc.update("update slot_offer set expires_at = now() - interval '1 minute' where status = 'PENDING'")
    }

    private data class Seeded(val email: String)

    /** Inserts a COMPLETED check-up in the past; there is no API for that. */
    private fun completedCheckUp(name: String, email: String, monthsAgo: Long): Seeded {
        val start = clinicTime.today().minusMonths(monthsAgo).at("08:00")
        val appointment = Appointment(
            reference = "DL-${9000 + (Math.random() * 999).toInt()}-${UUID.randomUUID().toString().take(4)}",
            practitioner = practitioners.findById(NORDVIK).orElseThrow(),
            treatmentType = treatmentTypes.findById(CHECK_UP).orElseThrow(),
            patient = Patient(name = name, phone = "+47 900 00 000", email = email),
            startTime = start,
            status = AppointmentStatus.COMPLETED,
            createdAt = start.minusSeconds(3600),
        )
        appointments.save(appointment)
        return Seeded(email)
    }
}
