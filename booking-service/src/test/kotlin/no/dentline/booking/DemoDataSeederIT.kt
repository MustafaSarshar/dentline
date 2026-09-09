package no.dentline.booking

import no.dentline.booking.seed.DemoDataSeeder
import no.dentline.booking.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.test.context.TestPropertySource
import java.time.DayOfWeek
import java.time.OffsetDateTime
import java.time.temporal.TemporalAdjusters

/** The demo seed must make every screen look populated, whatever day it runs on. */
@TestPropertySource(properties = ["dentline.seed.enabled=true"])
class DemoDataSeederIT : IntegrationTest() {
    @Autowired lateinit var seeder: DemoDataSeeder

    @Test
    fun `seeds a full week, a fully booked day, recalls and a live waitlist offer`() {
        seeder.run(DefaultApplicationArguments())

        val today = clinicTime.today()
        val anchor = if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) else today
        val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        // Today: every status on the board, including the no-show and the cancellation.
        val day = get("/api/staff/schedule?date=$anchor").body!!
        val statuses = day["practitioners"].flatMap { p -> p["appointments"].map { it["status"].asText() } }
        assertThat(statuses).contains("COMPLETED", "CONFIRMED", "REQUESTED", "NO_SHOW", "CANCELLED")
        assertThat(statuses.size).isGreaterThanOrEqualTo(9)

        // The week has one clinic-wide fully booked day and appointments every weekday.
        val week = get("/api/staff/schedule?from=$monday&to=${monday.plusDays(4)}").body!!["days"]
        assertThat(week.map { it["openSlotCount"].asInt() }).contains(0)
        assertThat(week.map { it["appointments"].size() }).allMatch { it > 0 }
        assertThat(week.sumOf { it["appointments"].size() }).isGreaterThanOrEqualTo(25)

        // Waitlist: six entries, one live countdown, one declined, one accepted with its booking.
        val waitlist = get("/api/staff/waitlist").body!!
        assertThat(waitlist).hasSize(6)
        val byStatus = waitlist.groupBy { it["status"].asText() }
        assertThat(byStatus.keys).containsExactlyInAnyOrder("WAITING", "OFFERED", "DECLINED", "ACCEPTED")
        val offered = byStatus.getValue("OFFERED").single()
        val expiresIn = java.time.Duration.between(OffsetDateTime.now(), OffsetDateTime.parse(offered["offer"]["expiresAt"].asText()))
        assertThat(expiresIn.toMinutes()).isBetween(11, 13)
        val accepted = byStatus.getValue("ACCEPTED").single()
        assertThat(accepted["appointmentId"].isNull).isFalse()
        assertThat(accepted["offer"]["status"].asText()).isEqualTo("ACCEPTED")
        assertThat(accepted["offer"]["respondedAt"].isNull).describedAs("drives 'Accepted 09:52'").isFalse()
        val declined = byStatus.getValue("DECLINED").single()
        assertThat(declined["offer"]["status"].asText()).isEqualTo("DECLINED")
        assertThat(declined["offer"]["respondedAt"].isNull).describedAs("drives 'Declined 11:04'").isFalse()

        // The stat cards have something to show.
        val metrics = get("/api/staff/metrics/summary").body!!
        assertThat(metrics["cancellationsThisWeek"]["value"].asLong()).isGreaterThanOrEqualTo(1)
        assertThat(metrics["waitlistAvgWaitDays"]["value"].asDouble()).isGreaterThan(1.0)

        // Recalls: six candidates, two already sent.
        val recalls = get("/api/staff/recalls").body!!
        assertThat(recalls).hasSize(6)
        assertThat(recalls.count { !it["recallSentAt"].isNull }).isEqualTo(2)

        assertThat(get("/api/staff/metrics/summary").statusCode.value()).isEqualTo(200)

        // Running again is a no-op.
        seeder.run(DefaultApplicationArguments())
        assertThat(get("/api/staff/waitlist").body!!).hasSize(6)
    }
}
