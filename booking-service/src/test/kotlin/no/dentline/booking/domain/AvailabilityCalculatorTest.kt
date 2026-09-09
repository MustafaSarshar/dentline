package no.dentline.booking.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

class AvailabilityCalculatorTest {
    private val calculator = AvailabilityCalculator(slotStep = Duration.ofMinutes(15))
    private val tuesday: LocalDate = LocalDate.of(2026, 9, 8)

    private fun t(hour: Int, minute: Int): LocalDateTime = tuesday.atTime(hour, minute)
    private fun window(start: String, end: String) = TimeWindow(LocalTime.parse(start), LocalTime.parse(end))
    private fun busy(start: String, end: String) = BusyInterval(tuesday.atTime(LocalTime.parse(start)), tuesday.atTime(LocalTime.parse(end)))
    private val thirtyMinutes: Duration = Duration.ofMinutes(30)

    @Test
    fun `slots step through the window and stop where the treatment would overrun the end`() {
        val slots = calculator.slots(tuesday, listOf(window("08:00", "09:00")), thirtyMinutes)

        // 08:45 + 30 min = 09:15 > 09:00, so it is not offered.
        assertThat(slots).containsExactly(t(8, 0), t(8, 15), t(8, 30))
    }

    @Test
    fun `a treatment that exactly fills the window is offered once`() {
        val slots = calculator.slots(tuesday, listOf(window("08:00", "08:30")), thirtyMinutes)

        assertThat(slots).containsExactly(t(8, 0))
    }

    @Test
    fun `a treatment longer than the window yields nothing`() {
        val slots = calculator.slots(tuesday, listOf(window("08:00", "09:00")), Duration.ofMinutes(90))

        assertThat(slots).isEmpty()
    }

    @Test
    fun `a treatment longer than what remains after a booking yields nothing`() {
        // 08:00–10:00 with 08:00–09:30 taken leaves 30 minutes, not enough for 45.
        val slots = calculator.slots(
            tuesday,
            listOf(window("08:00", "10:00")),
            Duration.ofMinutes(45),
            busy = listOf(busy("08:00", "09:30")),
        )

        assertThat(slots).isEmpty()
    }

    @Test
    fun `slots never span a lunch break between two windows`() {
        val slots = calculator.slots(
            tuesday,
            listOf(window("08:00", "11:30"), window("12:15", "15:30")),
            Duration.ofMinutes(60),
        )

        assertThat(slots).contains(t(10, 30)).doesNotContain(t(10, 45), t(11, 0), t(11, 15), t(11, 30), t(12, 0))
        assertThat(slots.first { it.toLocalTime() >= LocalTime.NOON }).isEqualTo(t(12, 15))
        assertThat(slots.last()).isEqualTo(t(14, 30))
    }

    @Test
    fun `booked appointments block every slot that would overlap them`() {
        val slots = calculator.slots(
            tuesday,
            listOf(window("08:00", "10:00")),
            thirtyMinutes,
            busy = listOf(busy("08:30", "09:15")),
        )

        // 08:15 would end 08:45, inside the booking; 09:00 would start inside it.
        assertThat(slots).containsExactly(t(8, 0), t(9, 15), t(9, 30))
    }

    @Test
    fun `a slot may end exactly when a booking starts and start exactly when it ends`() {
        val slots = calculator.slots(
            tuesday,
            listOf(window("08:00", "10:00")),
            thirtyMinutes,
            busy = listOf(busy("09:00", "09:30")),
        )

        assertThat(slots).contains(t(8, 30), t(9, 30)).doesNotContain(t(8, 45), t(9, 0), t(9, 15))
    }

    @Test
    fun `overlapping working windows do not produce duplicate slots`() {
        val slots = calculator.slots(
            tuesday,
            listOf(window("08:00", "10:00"), window("09:00", "11:00")),
            thirtyMinutes,
        )

        assertThat(slots).doesNotHaveDuplicates().isSorted()
        assertThat(slots.first()).isEqualTo(t(8, 0))
        assertThat(slots.last()).isEqualTo(t(10, 30))
        assertThat(slots).hasSize(11)
    }

    @Test
    fun `a day without working hours is closed`() {
        assertThat(calculator.slots(tuesday, emptyList(), thirtyMinutes)).isEmpty()
    }

    @Test
    fun `slots before now are not offered on the current day`() {
        val slots = calculator.slots(
            tuesday,
            listOf(window("08:00", "10:00")),
            thirtyMinutes,
            notBefore = t(9, 5),
        )

        assertThat(slots).containsExactly(t(9, 15), t(9, 30))
    }

    @Test
    fun `slot step is configurable`() {
        val halfHourly = AvailabilityCalculator(slotStep = Duration.ofMinutes(30))

        val slots = halfHourly.slots(tuesday, listOf(window("08:00", "10:00")), thirtyMinutes)

        assertThat(slots).containsExactly(t(8, 0), t(8, 30), t(9, 0), t(9, 30))
    }

    @Test
    fun `first available merges practitioners and keeps the first one per start time`() {
        val nordvik = UUID.randomUUID()
        val lund = UUID.randomUUID()

        val merged = calculator.firstAvailable(
            listOf(
                nordvik to listOf(t(8, 0), t(9, 0)),
                lund to listOf(t(8, 0), t(8, 30)),
            ),
        )

        assertThat(merged).containsExactly(
            AvailableSlot(t(8, 0), nordvik),
            AvailableSlot(t(8, 30), lund),
            AvailableSlot(t(9, 0), nordvik),
        )
    }

    @Test
    fun `invalid inputs are rejected up front`() {
        assertThatThrownBy { TimeWindow(LocalTime.of(9, 0), LocalTime.of(8, 0)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AvailabilityCalculator(Duration.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { calculator.slots(tuesday, emptyList(), Duration.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
