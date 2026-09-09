package no.dentline.booking

import com.fasterxml.jackson.databind.JsonNode
import no.dentline.booking.event.WaitlistEventType
import no.dentline.booking.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime

/**
 * The waitlist end to end. Offers are triggered by booking-service consuming its own CANCELLED /
 * RESCHEDULED events from Kafka, so every "offer appears" assertion below is also proof that the
 * after-commit relay, the topic and the consumer work.
 */
class WaitlistFlowIT : IntegrationTest() {
    private val eventually: Duration = Duration.ofSeconds(30)

    @Test
    fun `happy path - book, cancel, offer, accept`() {
        val monday = nextMonday()
        val ingridsAppointment = book(monday.at("08:00"))
        val vilde = joinWaitlist(windows = listOf("WEEKDAY_MORNINGS"))
        assertThat(vilde["status"].asText()).isEqualTo("WAITING")
        assertThat(vilde["position"].asInt()).isEqualTo(1)
        assertThat(vilde["queueSize"].asInt()).isEqualTo(1)

        post("/api/appointments/${ingridsAppointment["id"].asText()}/cancel")

        // The freed 08:00 window is offered to Vilde within seconds, via Kafka.
        val offered = awaitStatus(vilde.id(), "OFFERED")
        val offer = offered["offer"]
        assertThat(offer["status"].asText()).isEqualTo("PENDING")
        assertThat(offer["startTime"].localTime()).isEqualTo("08:00")
        assertThat(offer["practitioner"]["name"].asText()).isEqualTo("Dr. Astrid Nordvik")
        val expiresAt = OffsetDateTime.parse(offer["expiresAt"].asText())
        assertThat(expiresAt).isAfter(OffsetDateTime.now().plusMinutes(14)).isBefore(OffsetDateTime.now().plusMinutes(16)) // 15-minute hold
        // While the offer is pending the slot is held: nobody else can see or book it.
        assertThat(slotsOn(monday)).doesNotContain("08:00")
        assertThat(post("/api/appointments", bookingRequest(startTime = monday.at("08:00"))).body!!["code"].asText()).isEqualTo("SLOT_TAKEN")

        val accepted = post("/api/waitlist/${vilde.id()}/offer/accept")

        assertThat(accepted.statusCode.value()).isEqualTo(200)
        val appointment = accepted.body!!["appointment"]
        assertThat(appointment["status"].asText()).isEqualTo("CONFIRMED")
        assertThat(appointment["startTime"].localTime()).isEqualTo("08:00")
        assertThat(appointment["patient"]["name"].asText()).isEqualTo("Vilde Sørensen")
        assertThat(accepted.body!!["entry"]["status"].asText()).isEqualTo("ACCEPTED")
        assertThat(accepted.body!!["entry"]["appointmentId"].asText()).isEqualTo(appointment["id"].asText())
        assertThat(events.waitlist(WaitlistEventType.OFFER_ACCEPTED)).hasSize(1)

        // Accepting twice is a conflict, and the slot stays booked.
        assertThat(post("/api/waitlist/${vilde.id()}/offer/accept").body!!["code"].asText()).isEqualTo("OFFER_NOT_ACTIVE")
        assertThat(appointmentsStartingAt(monday.at("08:00"))).isEqualTo(1)
    }

    @Test
    fun `declining keeps the place in the queue and passes the slot to the next match`() {
        val monday = nextMonday()
        val appointment = book(monday.at("08:00"))
        val anders = joinWaitlist(name = "Anders Kolstad", email = "anders@example.com")
        val thea = joinWaitlist(name = "Thea Bjørnstad", email = "thea@example.com")
        assertThat(anders["position"].asInt()).isEqualTo(1)
        assertThat(thea["position"].asInt()).isEqualTo(2)

        post("/api/appointments/${appointment["id"].asText()}/cancel")
        awaitStatus(anders.id(), "OFFERED")

        val declined = post("/api/waitlist/${anders.id()}/offer/decline").body!!

        assertThat(declined["status"].asText()).isEqualTo("DECLINED")
        assertThat(declined["position"].asInt()).isEqualTo(1) // still first in line
        assertThat(declined["offer"]["status"].asText()).isEqualTo("DECLINED")
        assertThat(declined["offer"]["respondedAt"].isNull).isFalse()
        // The same window moved on to Thea in the same transaction.
        val theaNow = waitlistEntry(thea.id())
        assertThat(theaNow["status"].asText()).isEqualTo("OFFERED")
        assertThat(theaNow["offer"]["startTime"].localTime()).isEqualTo("08:00")
        // Anders is not offered the slot he just turned down again.
        post("/api/waitlist/${thea.id()}/offer/decline")
        assertThat(waitlistEntry(anders.id())["status"].asText()).isEqualTo("DECLINED")
        assertThat(slotsOn(monday)).contains("08:00") // nobody left to offer to: public again
    }

    @Test
    fun `entries that do not fit the freed window are skipped`() {
        val monday = nextMonday()
        val appointment = book(monday.at("08:00")) // frees a 30-minute window
        val eveningOnly = joinWaitlist(windows = listOf("AFTER_16"), name = "Late Larsen", email = "late@example.com")
        val rootCanal = joinWaitlist(treatmentTypeId = ROOT_CANAL, name = "Root Rønning", email = "root@example.com") // 90 min does not fit
        val withSaether = joinWaitlist(practitionerId = SAETHER, name = "Only Sæther", email = "saether@example.com")
        val checkUp = joinWaitlist(name = "Fits Fjeld", email = "fits@example.com")

        post("/api/appointments/${appointment["id"].asText()}/cancel")

        awaitStatus(checkUp.id(), "OFFERED")
        for (skipped in listOf(eveningOnly, rootCanal, withSaether)) {
            assertThat(waitlistEntry(skipped.id())["status"].asText()).describedAs(skipped["patient"]["name"].asText()).isEqualTo("WAITING")
        }
    }

    @Test
    fun `rescheduling frees the old window for the waitlist too`() {
        val monday = nextMonday()
        val appointment = book(monday.at("08:00"))
        val waiting = joinWaitlist()

        post("/api/appointments/${appointment["id"].asText()}/reschedule", mapOf("startTime" to monday.at("10:00").toString()))

        val offered = awaitStatus(waiting.id(), "OFFERED")
        assertThat(offered["offer"]["startTime"].localTime()).isEqualTo("08:00")
    }

    @Test
    fun `leaving the waitlist withdraws a pending offer and releases the hold`() {
        val monday = nextMonday()
        val appointment = book(monday.at("08:00"))
        val entry = joinWaitlist()
        post("/api/appointments/${appointment["id"].asText()}/cancel")
        awaitStatus(entry.id(), "OFFERED")
        assertThat(slotsOn(monday)).doesNotContain("08:00")

        assertThat(delete("/api/waitlist/${entry.id()}").statusCode.value()).isEqualTo(204)

        assertThat(waitlistEntry(entry.id())["status"].asText()).isEqualTo("REMOVED")
        assertThat(slotsOn(monday)).contains("08:00")
        assertThat(events.waitlist(WaitlistEventType.ENTRY_REMOVED)).hasSize(1)
    }

    @Test
    fun `staff can send an offer for the earliest matching free slot and withdraw it`() {
        val entry = joinWaitlist(windows = listOf("WEEKDAY_AFTERNOONS"), practitionerId = LUND)

        val offered = post("/api/staff/waitlist/${entry.id()}/offer").body!!

        assertThat(offered["status"].asText()).isEqualTo("OFFERED")
        val offer = offered["offer"]
        assertThat(offer["practitioner"]["id"].asText()).isEqualTo(LUND.toString())
        assertThat(offer["startTime"].localTime()).isGreaterThanOrEqualTo("12:15").isLessThan("16:00")
        assertThat(post("/api/staff/waitlist/${entry.id()}/offer").body!!["code"].asText()).isEqualTo("OFFER_ALREADY_PENDING")

        val withdrawn = post("/api/staff/waitlist/${entry.id()}/offer/withdraw").body!!

        assertThat(withdrawn["status"].asText()).isEqualTo("WAITING")
        assertThat(withdrawn["offer"]["status"].asText()).isEqualTo("WITHDRAWN")
        assertThat(events.waitlist(WaitlistEventType.OFFER_WITHDRAWN)).hasSize(1)
    }

    @Test
    fun `staff list shows per-treatment positions and accepted entries`() {
        joinWaitlist(treatmentTypeId = CLEANING, name = "First Cleaning", email = "c1@example.com")
        joinWaitlist(treatmentTypeId = CHECK_UP, name = "First Check-up", email = "cu1@example.com")
        joinWaitlist(treatmentTypeId = CLEANING, name = "Second Cleaning", email = "c2@example.com")

        val rows = get("/api/staff/waitlist").body!!

        assertThat(rows.map { it["patient"]["name"].asText() to it["position"].asInt() })
            .containsExactly("First Cleaning" to 1, "First Check-up" to 1, "Second Cleaning" to 2)
        assertThat(rows.map { it["queueSize"].asInt() }).containsExactly(2, 1, 2)
    }

    @Test
    fun `joining requires a treatment, a window and valid contact details`() {
        val response = post(
            "/api/waitlist",
            mapOf("treatmentTypeId" to CHECK_UP, "preferredWindows" to emptyList<String>(), "patient" to mapOf("name" to "V", "phone" to "1", "email" to "x")),
        )

        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body!!["errors"].map { it["field"].asText() })
            .containsExactly("patient.email", "patient.name", "patient.phone", "preferredWindows")
    }

    private fun JsonNode.id(): String = this["id"].asText()

    private fun awaitStatus(entryId: String, status: String): JsonNode {
        await().atMost(eventually).pollInterval(Duration.ofMillis(250)).untilAsserted {
            assertThat(waitlistEntry(entryId)["status"].asText()).isEqualTo(status)
        }
        return waitlistEntry(entryId)
    }
}
