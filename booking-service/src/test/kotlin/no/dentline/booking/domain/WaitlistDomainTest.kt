package no.dentline.booking.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate
import java.time.LocalDateTime

class WaitlistDomainTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 8) // Tuesday

    @Nested
    @DisplayName("preferred windows")
    inner class PreferredWindows {

        @ParameterizedTest(name = "{0} at {1} → {2}")
        @CsvSource(
            "WEEKDAY_MORNINGS,   2026-09-09T08:00, true",
            "WEEKDAY_MORNINGS,   2026-09-09T11:45, true",
            "WEEKDAY_MORNINGS,   2026-09-09T12:00, false",
            "WEEKDAY_MORNINGS,   2026-09-12T09:00, false",   // Saturday
            "WEEKDAY_AFTERNOONS, 2026-09-09T12:00, true",
            "WEEKDAY_AFTERNOONS, 2026-09-09T15:45, true",
            "WEEKDAY_AFTERNOONS, 2026-09-09T16:00, false",
            "AFTER_16,           2026-09-09T16:00, true",
            "AFTER_16,           2026-09-09T15:59, false",
            "ANY_THIS_WEEK,      2026-09-07T08:00, true",    // Monday of this week
            "ANY_THIS_WEEK,      2026-09-13T08:00, true",    // Sunday of this week
            "ANY_THIS_WEEK,      2026-09-14T08:00, false",   // next Monday
            "FRIDAYS_ONLY,       2026-09-11T13:45, true",
            "FRIDAYS_ONLY,       2026-09-10T13:45, false",
        )
        fun `window matching`(window: PreferredWindow, start: LocalDateTime, expected: Boolean) {
            assertThat(window.matches(start, today)).isEqualTo(expected)
        }
    }

    @Nested
    @DisplayName("waitlist entry")
    inner class Entry {

        @Test
        fun `requires at least one preferred window`() {
            assertThatThrownBy { Fixtures.waitlistEntry(windows = emptySet()) }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `wants a slot only for the chosen practitioner and a matching window`() {
            val nordvik = Fixtures.nordvik()
            val entry = Fixtures.waitlistEntry(windows = setOf(PreferredWindow.WEEKDAY_MORNINGS), practitioner = nordvik)
            val morning = LocalDateTime.of(2026, 9, 9, 8, 0)

            assertThat(entry.wantsSlot(morning, nordvik.id, today)).isTrue()
            assertThat(entry.wantsSlot(morning, Fixtures.id(), today)).isFalse()
            assertThat(entry.wantsSlot(morning.withHour(14), nordvik.id, today)).isFalse()
        }

        @Test
        fun `any practitioner is accepted when none was chosen`() {
            val entry = Fixtures.waitlistEntry(practitioner = null)

            assertThat(entry.wantsSlot(LocalDateTime.of(2026, 9, 9, 8, 0), Fixtures.id(), today)).isTrue()
        }

        @Test
        fun `declining keeps the patient in the queue and lets them be offered again`() {
            val entry = Fixtures.waitlistEntry()

            entry.markOffered(Fixtures.now)
            entry.decline(Fixtures.now)

            assertThat(entry.status).isEqualTo(WaitlistStatus.DECLINED)
            assertThat(entry.status.isInQueue).isTrue()
            entry.markOffered(Fixtures.now)
            assertThat(entry.status).isEqualTo(WaitlistStatus.OFFERED)
        }

        @Test
        fun `a second offer while one is pending is rejected`() {
            val entry = Fixtures.waitlistEntry()
            entry.markOffered(Fixtures.now)

            assertThatThrownBy { entry.markOffered(Fixtures.now) }
                .isInstanceOf(DomainConflictException::class.java)
                .extracting("code").isEqualTo(ConflictCode.OFFER_ALREADY_PENDING)
        }

        @Test
        fun `accepting stores the appointment and leaves the queue`() {
            val entry = Fixtures.waitlistEntry()
            val appointment = Fixtures.appointment(status = AppointmentStatus.CONFIRMED)
            entry.markOffered(Fixtures.now)

            entry.accept(appointment, Fixtures.now)

            assertThat(entry.status).isEqualTo(WaitlistStatus.ACCEPTED)
            assertThat(entry.status.isInQueue).isFalse()
            assertThat(entry.appointment).isSameAs(appointment)
            assertThatThrownBy { entry.markOffered(Fixtures.now) }.isInstanceOf(DomainConflictException::class.java)
        }

        @Test
        fun `responding without a pending offer is a conflict`() {
            val entry = Fixtures.waitlistEntry()

            assertThatThrownBy { entry.decline(Fixtures.now) }
                .isInstanceOf(DomainConflictException::class.java)
                .extracting("code").isEqualTo(ConflictCode.OFFER_NOT_ACTIVE)
        }
    }

    @Nested
    @DisplayName("slot offer")
    inner class Offer {

        @Test
        fun `is active until it expires`() {
            val offer = Fixtures.offer(Fixtures.waitlistEntry(), holdMinutes = 15)

            assertThat(offer.isActive(Fixtures.now.plusSeconds(14 * 60))).isTrue()
            assertThat(offer.isActive(Fixtures.now.plusSeconds(15 * 60))).isFalse()
        }

        @Test
        fun `accepting an expired offer is rejected`() {
            val offer = Fixtures.offer(Fixtures.waitlistEntry(), holdMinutes = 15)

            assertThatThrownBy { offer.accept(Fixtures.now.plusSeconds(16 * 60)) }
                .isInstanceOf(DomainConflictException::class.java)
                .extracting("code").isEqualTo(ConflictCode.OFFER_NOT_ACTIVE)
            assertThat(offer.status).isEqualTo(SlotOfferStatus.PENDING)
        }

        @Test
        fun `the sweep can expire a pending offer but not one that was answered`() {
            val offer = Fixtures.offer(Fixtures.waitlistEntry())
            offer.decline(Fixtures.now.plusSeconds(60))

            assertThatThrownBy { offer.expire(Fixtures.now.plusSeconds(20 * 60)) }.isInstanceOf(DomainConflictException::class.java)
            assertThat(offer.status).isEqualTo(SlotOfferStatus.DECLINED)
            assertThat(offer.respondedAt).isEqualTo(Fixtures.now.plusSeconds(60))
        }
    }
}
