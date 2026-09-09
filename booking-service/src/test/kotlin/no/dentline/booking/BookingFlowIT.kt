package no.dentline.booking

import no.dentline.booking.event.AppointmentEventType
import no.dentline.booking.event.CancelledBy
import no.dentline.booking.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.util.UUID

class BookingFlowIT : IntegrationTest() {

    @Test
    fun `booking a free slot creates a requested appointment with a DL reference`() {
        val monday = nextMonday()

        val created = book(monday.at("08:00"))

        assertThat(created["reference"].asText()).matches("DL-\\d{4}")
        assertThat(created["status"].asText()).isEqualTo("REQUESTED")
        assertThat(created["startTime"].localTime()).isEqualTo("08:00")
        assertThat(created["endTime"].localTime()).isEqualTo("08:30")
        assertThat(created["practitioner"]["name"].asText()).isEqualTo("Dr. Astrid Nordvik")
        assertThat(created["treatmentType"]["name"].asText()).isEqualTo("Check-up")
        assertThat(created["allowedActions"].map { it.asText() }).containsExactly("confirm", "no-show", "cancel", "reschedule")
        assertThat(events.ofType(AppointmentEventType.BOOKED)).singleElement()
            .extracting { it.appointment.reference }.isEqualTo(created["reference"].asText())
    }

    @Test
    fun `a booked slot disappears from availability and returns when cancelled`() {
        val monday = nextMonday()
        assertThat(slotsOn(monday)).contains("08:00", "08:15", "08:30")

        val appointment = book(monday.at("08:00"))
        // Any 30-minute slot overlapping 08:00–08:30 is gone; 08:30 itself is fine.
        assertThat(slotsOn(monday)).doesNotContain("08:00", "08:15").contains("08:30")

        val cancelled = post("/api/appointments/${appointment["id"].asText()}/cancel")
        assertThat(cancelled.statusCode.value()).isEqualTo(200)
        assertThat(cancelled.body!!["status"].asText()).isEqualTo("CANCELLED")
        assertThat(slotsOn(monday)).contains("08:00", "08:15")
        assertThat(events.ofType(AppointmentEventType.CANCELLED)).singleElement()
            .extracting { it.cancelledBy }.isEqualTo(CancelledBy.PATIENT)
    }

    @Test
    fun `validation errors name each field with the form's own copy`() {
        val response = post(
            "/api/appointments",
            bookingRequest(startTime = nextMonday().at("08:00"), name = "I", phone = "12345", email = "not-an-email"),
        )

        assertThat(response.statusCode.value()).isEqualTo(400)
        val errors = response.body!!["errors"].associate { it["field"].asText() to it["message"].asText() }
        assertThat(errors).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "patient.name" to "Please enter your full name",
                "patient.phone" to "Enter a phone number we can text",
                "patient.email" to "Enter a valid email address",
            ),
        )
    }

    @Test
    fun `booking outside working hours is a 409`() {
        val monday = nextMonday()
        val wednesday = monday.plusDays(2)

        val beforeOpening = post("/api/appointments", bookingRequest(startTime = monday.at("07:30")))
        val duringLunch = post("/api/appointments", bookingRequest(startTime = monday.at("11:15")))
        val onLundsDayOff = post("/api/appointments", bookingRequest(startTime = wednesday.at("09:00"), practitionerId = LUND))

        for (response in listOf(beforeOpening, duringLunch, onLundsDayOff)) {
            assertThat(response.statusCode.value()).isEqualTo(409)
            assertThat(response.body!!["code"].asText()).isEqualTo("OUTSIDE_WORKING_HOURS")
        }
    }

    @Test
    fun `booking in the past is a 409`() {
        val lastMonday = clinicTime.today().with(TemporalAdjusters.previous(DayOfWeek.MONDAY))

        val response = post("/api/appointments", bookingRequest(startTime = lastMonday.at("08:00")))

        assertThat(response.statusCode.value()).isEqualTo(409)
        assertThat(response.body!!["code"].asText()).isEqualTo("IN_THE_PAST")
    }

    @Test
    fun `unknown ids are 404`() {
        val unknown = UUID.randomUUID()

        assertThat(post("/api/appointments", bookingRequest(startTime = nextMonday().at("08:00"), treatmentTypeId = unknown)).statusCode.value()).isEqualTo(404)
        assertThat(post("/api/appointments", bookingRequest(startTime = nextMonday().at("08:00"), practitionerId = unknown)).statusCode.value()).isEqualTo(404)
        assertThat(get("/api/appointments/$unknown").statusCode.value()).isEqualTo(404)
        assertThat(post("/api/appointments/$unknown/confirm").statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `the front desk walks the state machine and illegal moves are 409`() {
        val id = book(nextMonday().at("09:00"))["id"].asText()

        val completeTooEarly = post("/api/appointments/$id/complete")
        assertThat(completeTooEarly.statusCode.value()).isEqualTo(409)
        assertThat(completeTooEarly.body!!["code"].asText()).isEqualTo("ILLEGAL_TRANSITION")

        val confirmed = post("/api/appointments/$id/confirm").body!!
        assertThat(confirmed["status"].asText()).isEqualTo("CONFIRMED")
        assertThat(confirmed["allowedActions"].map { it.asText() }).containsExactly("complete", "no-show", "cancel", "reschedule")

        val completed = post("/api/appointments/$id/complete").body!!
        assertThat(completed["status"].asText()).isEqualTo("COMPLETED")
        assertThat(completed["allowedActions"]).isEmpty()

        assertThat(post("/api/appointments/$id/cancel").statusCode.value()).isEqualTo(409)

        val history = get("/api/appointments/$id").body!!["history"].map { it["description"].asText() }
        assertThat(history).containsExactly(
            "Requested online by Ingrid",
            "Reminder scheduled for the day before",
            "Confirmed by front desk",
            "Completed by front desk",
        )
        assertThat(events.all.map { it.type }).containsExactly(
            AppointmentEventType.BOOKED, AppointmentEventType.CONFIRMED, AppointmentEventType.COMPLETED,
        )
    }

    @Test
    fun `rescheduling moves the appointment and frees the old slot`() {
        val monday = nextMonday()
        val appointment = book(monday.at("08:00"))
        val newStart = monday.at("10:00")

        val moved = post("/api/appointments/${appointment["id"].asText()}/reschedule", mapOf("startTime" to newStart.toString())).body!!

        assertThat(moved["status"].asText()).isEqualTo("REQUESTED")
        assertThat(moved["startTime"].localTime()).isEqualTo("10:00")
        assertThat(moved["endTime"].localTime()).isEqualTo("10:30")
        assertThat(slotsOn(monday)).contains("08:00").doesNotContain("10:00")
        assertThat(events.ofType(AppointmentEventType.RESCHEDULED)).singleElement()
            .extracting { it.previous!!.startTime }.isEqualTo(monday.at("08:00"))
    }

    @Test
    fun `rescheduling onto a taken slot is a 409 and the appointment stays put`() {
        val monday = nextMonday()
        book(monday.at("10:00"))
        val other = book(monday.at("08:00"))

        val response = post("/api/appointments/${other["id"].asText()}/reschedule", mapOf("startTime" to monday.at("10:00").toString()))

        assertThat(response.statusCode.value()).isEqualTo(409)
        assertThat(response.body!!["code"].asText()).isEqualTo("SLOT_TAKEN")
        assertThat(get("/api/appointments/${other["id"].asText()}").body!!["startTime"].localTime()).isEqualTo("08:00")
    }

    @Test
    fun `rescheduling to another practitioner works when the lock order differs`() {
        val monday = nextMonday()
        val appointment = book(monday.at("08:00"), practitionerId = LUND)

        val moved = post(
            "/api/appointments/${appointment["id"].asText()}/reschedule",
            mapOf("startTime" to monday.at("10:00").toString(), "practitionerId" to NORDVIK),
        ).body!!

        assertThat(moved["practitioner"]["id"].asText()).isEqualTo(NORDVIK.toString())
        assertThat(slotsOn(monday, practitionerId = LUND)).contains("08:00")
        assertThat(slotsOn(monday, practitionerId = NORDVIK)).doesNotContain("10:00")
    }

    @Test
    fun `staff cancellation is attributed to the front desk`() {
        val id = book(nextMonday().at("08:00"))["id"].asText()

        post("/api/appointments/$id/cancel", mapOf("by" to "STAFF"))

        assertThat(get("/api/appointments/$id").body!!["history"].last()["description"].asText()).isEqualTo("Cancelled by front desk")
        assertThat(events.ofType(AppointmentEventType.CANCELLED).single().cancelledBy).isEqualTo(CancelledBy.STAFF)
    }
}
