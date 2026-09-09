package no.dentline.booking.domain

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/** Small builders shared by the domain unit tests. */
object Fixtures {
    val now: Instant = Instant.parse("2026-09-08T09:20:00Z")

    fun checkUp() = TreatmentType(code = "CU", name = "Check-up", durationMinutes = 30, priceNok = BigDecimal("890.00"))

    fun rootCanal() = TreatmentType(code = "RC", name = "Root canal", durationMinutes = 90, priceNok = BigDecimal("4500.00"))

    fun nordvik(): Practitioner {
        val p = Practitioner(name = "Dr. Astrid Nordvik", title = PractitionerTitle.DENTIST)
        for (day in listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            p.addWorkingHours(day, LocalTime.of(8, 0), LocalTime.of(11, 30))
            p.addWorkingHours(day, LocalTime.of(12, 15), LocalTime.of(15, 30))
        }
        return p
    }

    fun ingrid() = Patient(name = "Ingrid Bakken", phone = "+47 913 44 208", email = "Ingrid.Bakken@gmail.com")

    fun appointment(
        status: AppointmentStatus = AppointmentStatus.REQUESTED,
        treatmentType: TreatmentType = checkUp(),
        practitioner: Practitioner = nordvik(),
        startTime: Instant = Instant.parse("2026-09-16T07:15:00Z"),
    ) = Appointment(
        reference = "DL-4821",
        practitioner = practitioner,
        treatmentType = treatmentType,
        patient = ingrid(),
        startTime = startTime,
        status = status,
        createdAt = now,
    )

    fun waitlistEntry(
        windows: Set<PreferredWindow> = setOf(PreferredWindow.WEEKDAY_MORNINGS),
        practitioner: Practitioner? = null,
        treatmentType: TreatmentType = checkUp(),
    ) = WaitlistEntry(
        patient = ingrid(),
        treatmentType = treatmentType,
        practitioner = practitioner,
        preferredWindows = windows,
        createdAt = now,
    )

    fun offer(entry: WaitlistEntry, createdAt: Instant = now, holdMinutes: Long = 15) = SlotOffer(
        waitlistEntry = entry,
        practitioner = nordvik(),
        startTime = Instant.parse("2026-09-11T11:45:00Z"),
        endTime = Instant.parse("2026-09-11T12:30:00Z"),
        expiresAt = createdAt.plusSeconds(holdMinutes * 60),
        createdAt = createdAt,
    )

    fun id(): UUID = UUID.randomUUID()
}
