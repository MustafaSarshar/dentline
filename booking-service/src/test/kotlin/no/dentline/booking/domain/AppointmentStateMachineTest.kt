package no.dentline.booking.domain

import no.dentline.booking.domain.AppointmentStatus.CANCELLED
import no.dentline.booking.domain.AppointmentStatus.COMPLETED
import no.dentline.booking.domain.AppointmentStatus.CONFIRMED
import no.dentline.booking.domain.AppointmentStatus.NO_SHOW
import no.dentline.booking.domain.AppointmentStatus.REQUESTED
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import java.time.Duration
import java.time.Instant

class AppointmentStateMachineTest {

    @Nested
    @DisplayName("transition table")
    inner class TransitionTable {

        @ParameterizedTest(name = "{0} → {1} is legal")
        @CsvSource(
            "REQUESTED, CONFIRMED",
            "REQUESTED, CANCELLED",
            "REQUESTED, NO_SHOW",
            "CONFIRMED, COMPLETED",
            "CONFIRMED, CANCELLED",
            "CONFIRMED, NO_SHOW",
        )
        fun `legal transitions`(from: AppointmentStatus, to: AppointmentStatus) {
            assertThat(from.canTransitionTo(to)).isTrue()
        }

        @ParameterizedTest(name = "{0} → {1} is illegal")
        @CsvSource(
            "REQUESTED, COMPLETED",   // must be confirmed first
            "REQUESTED, REQUESTED",
            "CONFIRMED, REQUESTED",   // no going back
            "CONFIRMED, CONFIRMED",
            "COMPLETED, CANCELLED",
            "COMPLETED, NO_SHOW",
            "COMPLETED, CONFIRMED",
            "CANCELLED, CONFIRMED",
            "CANCELLED, COMPLETED",
            "NO_SHOW, COMPLETED",
            "NO_SHOW, CANCELLED",
        )
        fun `illegal transitions`(from: AppointmentStatus, to: AppointmentStatus) {
            assertThat(from.canTransitionTo(to)).isFalse()
        }

        @ParameterizedTest
        @EnumSource(value = AppointmentStatus::class, names = ["COMPLETED", "CANCELLED", "NO_SHOW"])
        fun `terminal states allow nothing`(status: AppointmentStatus) {
            assertThat(status.isTerminal).isTrue()
            assertThat(status.transitions).isEmpty()
            assertThat(status.allowedActions).isEmpty()
        }

        @Test
        fun `only requested and confirmed appointments occupy the calendar`() {
            assertThat(AppointmentStatus.entries.filter { it.blocksCalendar }).containsExactly(REQUESTED, CONFIRMED)
        }

        @Test
        fun `allowed actions mirror the transitions plus reschedule while the slot is live`() {
            assertThat(REQUESTED.allowedActions).containsExactly(
                AppointmentAction.CONFIRM, AppointmentAction.NO_SHOW, AppointmentAction.CANCEL, AppointmentAction.RESCHEDULE,
            )
            assertThat(CONFIRMED.allowedActions).containsExactly(
                AppointmentAction.COMPLETE, AppointmentAction.NO_SHOW, AppointmentAction.CANCEL, AppointmentAction.RESCHEDULE,
            )
        }
    }

    @Nested
    @DisplayName("appointment behaviour")
    inner class AppointmentBehaviour {
        private val later: Instant = Fixtures.now.plusSeconds(3600)

        @Test
        fun `an online booking starts as requested with its history written`() {
            val appointment = Appointment.book(
                reference = "DL-4821",
                practitioner = Fixtures.nordvik(),
                treatmentType = Fixtures.checkUp(),
                patient = Fixtures.ingrid(),
                startTime = Instant.parse("2026-09-16T07:15:00Z"),
                now = Fixtures.now,
            )

            assertThat(appointment.status).isEqualTo(REQUESTED)
            assertThat(appointment.endTime).isEqualTo(appointment.startTime + Duration.ofMinutes(30))
            assertThat(appointment.history.map { it.type })
                .containsExactly(HistoryType.BOOKED, HistoryType.REMINDER_SCHEDULED)
            assertThat(appointment.history.first().description).isEqualTo("Requested online by Ingrid")
        }

        @Test
        fun `a booking from an accepted offer is confirmed immediately`() {
            val appointment = Appointment.fromAcceptedOffer(
                reference = "DL-4830",
                practitioner = Fixtures.nordvik(),
                treatmentType = Fixtures.checkUp(),
                patient = Fixtures.ingrid(),
                startTime = Instant.parse("2026-09-16T07:15:00Z"),
                now = Fixtures.now,
            )

            assertThat(appointment.status).isEqualTo(CONFIRMED)
            assertThat(appointment.history.map { it.type })
                .containsExactly(HistoryType.BOOKED, HistoryType.CONFIRMED, HistoryType.REMINDER_SCHEDULED)
        }

        @Test
        fun `confirm then complete walks the happy path and records who did it`() {
            val appointment = Fixtures.appointment(status = REQUESTED)

            appointment.confirm(Fixtures.now, "front desk")
            appointment.complete(later, "front desk")

            assertThat(appointment.status).isEqualTo(COMPLETED)
            assertThat(appointment.updatedAt).isEqualTo(later)
            assertThat(appointment.history.map { it.description })
                .containsExactly("Confirmed by front desk", "Completed by front desk")
        }

        @Test
        fun `completing a requested appointment is rejected and leaves it untouched`() {
            val appointment = Fixtures.appointment(status = REQUESTED)

            assertThatThrownBy { appointment.complete(later, "front desk") }
                .isInstanceOf(IllegalTransitionException::class.java)
                .hasMessage("Cannot move an appointment from REQUESTED to COMPLETED")
                .extracting("code").isEqualTo(ConflictCode.ILLEGAL_TRANSITION)

            assertThat(appointment.status).isEqualTo(REQUESTED)
            assertThat(appointment.updatedAt).isEqualTo(Fixtures.now)
            assertThat(appointment.history).isEmpty()
        }

        @Test
        fun `a cancelled appointment cannot be revived`() {
            val appointment = Fixtures.appointment(status = REQUESTED)
            appointment.cancel(Fixtures.now, "patient")

            assertThatThrownBy { appointment.confirm(later, "front desk") }.isInstanceOf(IllegalTransitionException::class.java)
            assertThatThrownBy { appointment.markNoShow(later, "front desk") }.isInstanceOf(IllegalTransitionException::class.java)
            assertThat(appointment.status).isEqualTo(CANCELLED)
        }

        @Test
        fun `no-show is terminal`() {
            val appointment = Fixtures.appointment(status = CONFIRMED)
            appointment.markNoShow(Fixtures.now, "front desk")

            assertThat(appointment.status).isEqualTo(NO_SHOW)
            assertThatThrownBy { appointment.complete(later, "front desk") }.isInstanceOf(IllegalTransitionException::class.java)
        }

        @Test
        fun `rescheduling keeps the status and recomputes the end time from the treatment`() {
            val rootCanal = Fixtures.rootCanal()
            val appointment = Fixtures.appointment(status = CONFIRMED, treatmentType = rootCanal)
            val newStart = Instant.parse("2026-09-18T10:15:00Z")
            val otherPractitioner = Practitioner(name = "Dr. Henrik Sæther", title = PractitionerTitle.DENTIST)

            appointment.reschedule(newStart, otherPractitioner, later, "patient")

            assertThat(appointment.status).isEqualTo(CONFIRMED)
            assertThat(appointment.startTime).isEqualTo(newStart)
            assertThat(appointment.endTime).isEqualTo(newStart + Duration.ofMinutes(90))
            assertThat(appointment.practitioner).isSameAs(otherPractitioner)
            assertThat(appointment.history.last().type).isEqualTo(HistoryType.RESCHEDULED)
        }

        @ParameterizedTest
        @EnumSource(value = AppointmentStatus::class, names = ["COMPLETED", "CANCELLED", "NO_SHOW"])
        fun `terminal appointments cannot be rescheduled`(status: AppointmentStatus) {
            val appointment = Fixtures.appointment(status = status)

            assertThatThrownBy { appointment.reschedule(later, Fixtures.nordvik(), later, "patient") }
                .isInstanceOf(DomainConflictException::class.java)
                .extracting("code").isEqualTo(ConflictCode.ILLEGAL_TRANSITION)
        }
    }
}
