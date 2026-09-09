package no.dentline.booking

import no.dentline.booking.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class MetricsSummaryIT : IntegrationTest() {

    @Test
    fun `the summary reflects bookings, cancellations and the waitlist`() {
        val monday = nextMonday()
        val cancelled = book(monday.at("08:00"))
        book(monday.at("09:00"))
        post("/api/appointments/${cancelled["id"].asText()}/cancel")
        joinWaitlist()
        joinWaitlist(name = "Second", email = "second@example.com")

        val summary = get("/api/staff/metrics/summary").body!!

        assertThat(summary["bookingsToday"]["value"].asLong()).isGreaterThanOrEqualTo(0)
        assertThat(summary["cancellationsThisWeek"]["value"].asLong()).isEqualTo(1)
        assertThat(summary["cancellationsThisWeek"]["refilledFromWaitlist"].asLong()).isEqualTo(0)
        assertThat(summary["noShowRate"]["value"].asDouble()).isCloseTo(0.0, within(1e-9))
        assertThat(summary["waitlistAvgWaitDays"]["queued"].asLong()).isEqualTo(2)
        assertThat(summary["waitlistAvgWaitDays"]["value"].asDouble()).isCloseTo(0.0, within(1e-9))
    }
}
