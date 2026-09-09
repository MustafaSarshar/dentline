package no.dentline.booking

import no.dentline.booking.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import java.time.Duration

/** Boot disables metrics export in tests by default; this switches it back on for the scrape. */
@AutoConfigureObservability
class PrometheusMetricsIT : IntegrationTest() {

    @Test
    fun `bookings, transitions and latency show up on the Prometheus endpoint`() {
        val monday = nextMonday()
        val id = book(monday.at("08:00"))["id"].asText()
        post("/api/appointments/$id/confirm")
        post("/api/appointments/$id/cancel", mapOf("by" to "STAFF"))
        post("/api/appointments", bookingRequest(startTime = monday.at("07:00"))) // rejected: outside working hours

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val scrape = rest.getForObject("/actuator/prometheus", String::class.java)!!
            fun series(name: String, vararg labels: String): Double? =
                scrape.lineSequence()
                    .filter { it.startsWith(name) && labels.all { l -> it.contains(l) } }
                    .map { it.substringAfterLast(' ').toDouble() }
                    .firstOrNull()

            assertThat(series("dentline_appointments_total", "event=\"BOOKED\"")).isEqualTo(1.0)
            assertThat(series("dentline_appointments_total", "event=\"CONFIRMED\"")).isEqualTo(1.0)
            assertThat(series("dentline_appointments_total", "event=\"CANCELLED\"")).isEqualTo(1.0)
            assertThat(series("dentline_cancellations_total", "by=\"STAFF\"")).isEqualTo(1.0)
            assertThat(series("dentline_booking_latency_seconds_count", "outcome=\"created\"")).isEqualTo(1.0)
            assertThat(series("dentline_booking_latency_seconds_count", "outcome=\"rejected\"")).isEqualTo(1.0)
            assertThat(scrape).contains("dentline_booking_latency_seconds_bucket")
            assertThat(series("dentline_no_show_rate")).isNotNull()
            assertThat(series("dentline_waitlist_offer_acceptance_rate")).isNotNull()
            assertThat(series("dentline_waitlist_offers_total", "outcome=\"SENT\"")).isEqualTo(0.0)
        }
    }
}
