package no.dentline.booking

import no.dentline.booking.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AvailabilityIT : IntegrationTest() {

    @Test
    fun `weekends are closed and weekdays list slots from the seeded working hours`() {
        val monday = nextMonday()

        val days = get("/api/availability?treatmentTypeId=$CHECK_UP&practitionerId=$NORDVIK&from=$monday&to=${monday.plusDays(6)}")
            .body!!["days"]

        assertThat(days).hasSize(7)
        assertThat(days.map { it["closed"].asBoolean() }).containsExactly(false, false, false, false, false, true, true)
        val mondaySlots = days[0]["slots"].map { it["startTime"].localTime() }
        assertThat(mondaySlots.first()).isEqualTo("08:00")
        assertThat(mondaySlots).contains("11:00").doesNotContain("11:15", "11:30", "12:00").contains("12:15")
        assertThat(mondaySlots.last()).isEqualTo("15:00")
    }

    @Test
    fun `a practitioner's day off is closed while the clinic as a whole is open`() {
        val wednesday = nextMonday().plusDays(2)

        val lund = get("/api/availability?treatmentTypeId=$CHECK_UP&practitionerId=$LUND&from=$wednesday&to=$wednesday").body!!["days"][0]
        val clinic = get("/api/availability?treatmentTypeId=$CHECK_UP&from=$wednesday&to=$wednesday").body!!["days"][0]

        assertThat(lund["closed"].asBoolean()).isTrue()
        assertThat(clinic["closed"].asBoolean()).isFalse()
    }

    @Test
    fun `first available merges practitioners and attributes each slot to one of them`() {
        val monday = nextMonday()

        val before = get("/api/availability?treatmentTypeId=$CLEANING&from=$monday&to=$monday").body!!["days"][0]["slots"]
        val eightBefore = before.first { it["startTime"].localTime() == "08:00" }
        assertThat(eightBefore["practitionerId"].asText()).isEqualTo(NORDVIK.toString()) // first by name

        book(monday.at("08:00"), practitionerId = NORDVIK, treatmentTypeId = CLEANING)

        val after = get("/api/availability?treatmentTypeId=$CLEANING&from=$monday&to=$monday").body!!["days"][0]["slots"]
        val eightAfter = after.first { it["startTime"].localTime() == "08:00" }
        assertThat(eightAfter["practitionerId"].asText()).isEqualTo(LUND.toString()) // Nordvik is busy, Lund still free
        assertThat(after.map { it["startTime"].localTime() }).doesNotHaveDuplicates().isSorted()
    }

    @Test
    fun `slots are grouped for the frontend as morning and afternoon by start time only`() {
        // The API does not group; it just guarantees ascending order so the client can split at 12:00.
        val slots = slotsOn(nextMonday())

        assertThat(slots).isSorted()
        assertThat(slots.count { it < "12:00" }).isGreaterThan(0)
        assertThat(slots.count { it >= "12:00" }).isGreaterThan(0)
    }

    @Test
    fun `invalid ranges are 400`() {
        val monday = nextMonday()

        val backwards = get("/api/availability?treatmentTypeId=$CHECK_UP&from=$monday&to=${monday.minusDays(1)}")
        val tooLong = get("/api/availability?treatmentTypeId=$CHECK_UP&from=$monday&to=${monday.plusDays(40)}")
        val missing = get("/api/availability?from=$monday&to=$monday")

        assertThat(backwards.statusCode.value()).isEqualTo(400)
        assertThat(tooLong.statusCode.value()).isEqualTo(400)
        assertThat(missing.statusCode.value()).isEqualTo(400)
    }
}
