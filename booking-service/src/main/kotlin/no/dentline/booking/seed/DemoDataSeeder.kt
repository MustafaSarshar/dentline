package no.dentline.booking.seed

import no.dentline.booking.domain.Appointment
import no.dentline.booking.domain.AppointmentStatus
import no.dentline.booking.domain.Patient
import no.dentline.booking.domain.Practitioner
import no.dentline.booking.domain.PreferredWindow
import no.dentline.booking.domain.RecallNotice
import no.dentline.booking.domain.SlotOffer
import no.dentline.booking.domain.TreatmentType
import no.dentline.booking.domain.WaitlistEntry
import no.dentline.booking.event.AppointmentEvent
import no.dentline.booking.event.AppointmentEventType
import no.dentline.booking.event.AppointmentSnapshot
import no.dentline.booking.event.PatientSnapshot
import no.dentline.booking.event.RecallEvent
import no.dentline.booking.event.RecallEventType
import no.dentline.booking.event.WaitlistEventType
import no.dentline.booking.repository.AppointmentRepository
import no.dentline.booking.repository.PractitionerRepository
import no.dentline.booking.repository.RecallNoticeRepository
import no.dentline.booking.repository.SlotOfferRepository
import no.dentline.booking.repository.TreatmentTypeRepository
import no.dentline.booking.repository.WaitlistEntryRepository
import no.dentline.booking.service.AppointmentService
import no.dentline.booking.service.AvailabilityService
import no.dentline.booking.service.ClinicTime
import no.dentline.booking.service.WaitlistQueue
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Demo data relative to today, so every screen looks lived-in on first `docker compose up`:
 * a full working week around today with every status (one no-show, one cancellation), one
 * clinic-wide fully booked day, six recall candidates, and a six-person waitlist with one live
 * offer counting down. Runs only when `dentline.seed.enabled=true` and the database has no
 * appointments yet. Events are published like real activity, so notification-service fills up too.
 */
@Component
@ConditionalOnProperty("dentline.seed.enabled", havingValue = "true")
class DemoDataSeeder(
    private val appointments: AppointmentRepository,
    private val practitioners: PractitionerRepository,
    private val treatmentTypes: TreatmentTypeRepository,
    private val waitlist: WaitlistEntryRepository,
    private val offers: SlotOfferRepository,
    private val recallNotices: RecallNoticeRepository,
    private val appointmentService: AppointmentService,
    private val availability: AvailabilityService,
    private val queue: WaitlistQueue,
    private val clinicTime: ClinicTime,
    private val events: ApplicationEventPublisher,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    private lateinit var byCode: Map<String, TreatmentType>
    private lateinit var nordvik: Practitioner
    private lateinit var saether: Practitioner
    private lateinit var lund: Practitioner

    @Transactional
    override fun run(args: ApplicationArguments) {
        if (appointments.count() > 0) {
            log.info("Demo seed skipped: database already has appointments")
            return
        }
        byCode = treatmentTypes.findAll().associateBy { it.code }
        nordvik = practitioners.findById(NORDVIK).orElseThrow()
        saether = practitioners.findById(SAETHER).orElseThrow()
        lund = practitioners.findById(LUND).orElseThrow()

        val today = clinicTime.today()
        // On a weekend, anchor the demo week on the coming Monday so the week view is populated.
        val anchor = if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) else today
        val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val fullDay = if (anchor.dayOfWeek == DayOfWeek.THURSDAY) DayOfWeek.WEDNESDAY else DayOfWeek.THURSDAY

        seedWeek(monday, anchor, fullDay)
        seedRecallCandidates(today)
        seedWaitlist(anchor)
        log.info("Demo seed complete: {} appointments, {} waitlist entries", appointments.count(), waitlist.count())
    }

    // ---------------------------------------------------------------- week

    private data class Planned(val practitioner: Practitioner, val time: String, val patient: Patient, val code: String, val status: AppointmentStatus)

    private fun seedWeek(monday: LocalDate, anchor: LocalDate, fullDay: DayOfWeek) {
        for (offset in 0L..4L) {
            val date = monday.plusDays(offset)
            val plan = when {
                date == anchor -> todaysPlan()
                date.dayOfWeek == fullDay -> fullDayPlan(date.dayOfWeek)
                else -> weekPlan(date.dayOfWeek)
            }
            val placed = mutableMapOf<UUID, MutableList<Pair<LocalTime, LocalTime>>>()
            for (p in plan) {
                val treatment = byCode.getValue(p.code)
                val (practitioner, start) = practitionerFor(date, LocalTime.parse(p.time), treatment, p.practitioner, placed) ?: continue
                val status = when {
                    date.isBefore(anchor) -> if (p.status == AppointmentStatus.NO_SHOW || p.status == AppointmentStatus.CANCELLED) p.status else AppointmentStatus.COMPLETED
                    date.isAfter(anchor) -> if (p.status == AppointmentStatus.REQUESTED) p.status else AppointmentStatus.CONFIRMED
                    else -> p.status
                }
                createAppointment(practitioner, date, start.toString(), p.patient, treatment, status, publish = true)
            }
        }
    }

    /**
     * The planned practitioner at the planned time, or — when they are off that day or already
     * booked then — the first colleague and 15-minute step from that time onwards where the
     * treatment fits, so the demo keeps every status whatever weekday it runs on.
     */
    private fun practitionerFor(
        date: LocalDate,
        plannedStart: LocalTime,
        treatment: TreatmentType,
        preferred: Practitioner,
        placed: MutableMap<UUID, MutableList<Pair<LocalTime, LocalTime>>>,
    ): Pair<Practitioner, LocalTime>? {
        val duration = treatment.durationMinutes.toLong()
        val candidates = listOf(preferred) + listOf(nordvik, saether, lund).filter { it.id != preferred.id }
        for (p in candidates) {
            val windows = p.workingWindowsOn(date.dayOfWeek)
            var start = plannedStart
            while (start.isBefore(LocalTime.of(18, 0))) {
                val end = start.plusMinutes(duration)
                val fits = windows.any { !start.isBefore(it.start) && !end.isAfter(it.end) }
                val free = placed[p.id].orEmpty().none { (s, e) -> start.isBefore(e) && end.isAfter(s) }
                if (fits && free) {
                    placed.getOrPut(p.id) { mutableListOf() } += start to end
                    return p to start
                }
                start = start.plusMinutes(15)
            }
        }
        return null
    }

    private fun todaysPlan() = listOf(
        Planned(nordvik, "08:00", patient("Ingrid Bakken", "+47 913 44 208", "ingrid.bakken@gmail.com"), "CU", AppointmentStatus.COMPLETED),
        Planned(nordvik, "09:15", patient("Omar Hussain", "+47 476 21 903", "omar.hussain@icloud.com"), "FI", AppointmentStatus.COMPLETED),
        Planned(nordvik, "12:15", patient("Kjersti Aune", "+47 934 07 551", "kjersti.aune@online.no"), "RC", AppointmentStatus.CONFIRMED),
        Planned(nordvik, "14:00", patient("Tobias Fjeld", "+47 401 88 032", "tobias.fjeld@gmail.com"), "FI", AppointmentStatus.REQUESTED),
        Planned(saether, "10:00", patient("Line Haugland", "+47 926 13 774", "line.haugland@gmail.com"), "CL", AppointmentStatus.NO_SHOW),
        Planned(saether, "11:15", patient("Sondre Vik", "+47 458 90 116", "sondre.vik@vikas.no"), "RC", AppointmentStatus.CONFIRMED),
        Planned(saether, "15:00", patient("Amina Yusuf", "+47 483 26 700", "amina.yusuf@gmail.com"), "WH", AppointmentStatus.REQUESTED),
        Planned(lund, "08:00", patient("Erlend Strøm", "+47 906 55 341", "erlend.strom@hotmail.com"), "CU", AppointmentStatus.COMPLETED),
        Planned(lund, "09:00", patient("Marte Rønning", "+47 971 42 618", "marte.ronning@gmail.com"), "RC", AppointmentStatus.CANCELLED),
        Planned(lund, "12:15", patient("Jonas Berge", "+47 468 71 205", "jonas.berge@gmail.com"), "CU", AppointmentStatus.CONFIRMED),
        Planned(lund, "13:45", patient("Sara Nyland", "+47 917 30 884", "sara.nyland@gmail.com"), "CL", AppointmentStatus.REQUESTED),
    )

    private fun weekPlan(day: DayOfWeek): List<Planned> = when (day) {
        DayOfWeek.MONDAY -> listOf(
            Planned(nordvik, "08:00", patient("Vetle Aasen"), "CU", AppointmentStatus.COMPLETED),
            Planned(lund, "09:30", patient("Hedda Moen"), "CL", AppointmentStatus.COMPLETED),
            Planned(saether, "11:15", patient("Petter Dahl"), "FI", AppointmentStatus.NO_SHOW),
            Planned(nordvik, "13:00", patient("Nora Eide"), "WH", AppointmentStatus.COMPLETED),
            Planned(saether, "15:00", patient("Ali Rashid"), "CU", AppointmentStatus.COMPLETED),
            Planned(lund, "14:00", patient("Kari Bjelland"), "CL", AppointmentStatus.CANCELLED),
        )
        DayOfWeek.TUESDAY -> listOf(
            Planned(nordvik, "08:30", patient("Silje Aarø"), "CL", AppointmentStatus.CONFIRMED),
            Planned(saether, "10:00", patient("Bjørn Tveter"), "CU", AppointmentStatus.CONFIRMED),
            Planned(lund, "12:15", patient("Frida Holm"), "FI", AppointmentStatus.CONFIRMED),
            Planned(saether, "16:00", patient("Kasper Lie"), "WH", AppointmentStatus.REQUESTED),
        )
        DayOfWeek.WEDNESDAY -> listOf(
            Planned(nordvik, "08:30", patient("Silje Aarø"), "CL", AppointmentStatus.CONFIRMED),
            Planned(saether, "10:00", patient("Bjørn Tveter"), "CU", AppointmentStatus.CONFIRMED),
            Planned(nordvik, "12:15", patient("Frida Holm"), "FI", AppointmentStatus.CONFIRMED),
            Planned(saether, "16:00", patient("Kasper Lie"), "WH", AppointmentStatus.REQUESTED),
        )
        DayOfWeek.THURSDAY -> listOf(
            Planned(nordvik, "08:00", patient("Elias Rud"), "RC", AppointmentStatus.CONFIRMED),
            Planned(lund, "09:45", patient("Tone Sandvik"), "CL", AppointmentStatus.CONFIRMED),
            Planned(saether, "11:00", patient("Håkon Grøn"), "CU", AppointmentStatus.CONFIRMED),
            Planned(lund, "12:15", patient("Yasmin Ali"), "FI", AppointmentStatus.CONFIRMED),
            Planned(saether, "14:30", patient("Trygve Foss"), "CU", AppointmentStatus.CONFIRMED),
        )
        DayOfWeek.FRIDAY -> listOf(
            Planned(nordvik, "08:00", patient("Mia Bakke"), "CU", AppointmentStatus.CONFIRMED),
            Planned(lund, "10:30", patient("Lars Ødegård"), "FI", AppointmentStatus.REQUESTED),
            Planned(nordvik, "13:00", patient("Signe Vold"), "CU", AppointmentStatus.CANCELLED),
        )
        else -> emptyList()
    }

    /** Fills every working window of every practitioner so no 30-minute slot is left. */
    private fun fullDayPlan(day: DayOfWeek): List<Planned> {
        val cycle = listOf("RC", "FI", "CL", "CU", "WH")
        val names = FULL_DAY_NAMES.iterator()
        val plan = mutableListOf<Planned>()
        for (practitioner in listOf(nordvik, saether, lund)) {
            for (window in practitioner.workingWindowsOn(day)) {
                var cursor = window.start
                var i = 0
                while (Duration.between(cursor, window.end).toMinutes() >= 30) {
                    val remaining = Duration.between(cursor, window.end).toMinutes()
                    val code = (0 until cycle.size).map { cycle[(i + it) % cycle.size] }.first { byCode.getValue(it).durationMinutes <= remaining }
                    plan += Planned(practitioner, cursor.toString(), patient(names.next()), code, AppointmentStatus.CONFIRMED)
                    cursor = cursor.plusMinutes(byCode.getValue(code).durationMinutes.toLong())
                    i++
                }
            }
        }
        return plan
    }

    // ------------------------------------------------------------- recalls

    private fun seedRecallCandidates(today: LocalDate) {
        val checkUp = byCode.getValue("CU")
        data class Candidate(val name: String, val practitioner: Practitioner, val lastVisit: LocalDate, val recallSentDaysAgo: Long?)
        val candidates = listOf(
            Candidate("Hedda Moen", nordvik, today.minusMonths(6).minusDays(4), 6),
            Candidate("Vetle Aas", lund, today.minusMonths(6).plusDays(10), 4),
            Candidate("Bjørn Tvedt", saether, today.minusMonths(6).plusDays(16), null),
            Candidate("Frida Holm", lund, today.minusMonths(6).plusDays(21), null),
            Candidate("Ali Rahimi", nordvik, today.minusMonths(6).plusDays(24), null),
            Candidate("Tone Sandvik", saether, today.minusMonths(6).plusDays(28), null),
        )
        for (c in candidates) {
            val date = c.lastVisit.let { if (it.dayOfWeek == DayOfWeek.SATURDAY) it.minusDays(1) else if (it.dayOfWeek == DayOfWeek.SUNDAY) it.minusDays(2) else it }
            val time = c.practitioner.workingWindowsOn(date.dayOfWeek).firstOrNull()?.start ?: LocalTime.of(10, 0)
            val p = patient(c.name)
            createAppointment(c.practitioner, date, time.toString(), p, checkUp, AppointmentStatus.COMPLETED, publish = false)
            c.recallSentDaysAgo?.let { daysAgo ->
                val sentAt = clinicTime.toInstant(today.minusDays(daysAgo).atTime(6, 0))
                recallNotices.save(RecallNotice(p.normalizedEmail, p.name, p.phone, sentAt))
                events.publishEvent(
                    RecallEvent(RecallEventType.RECALL_DUE, sentAt, PatientSnapshot(p.name, p.phone, p.email), date, date.plusMonths(6), c.practitioner.name),
                )
            }
        }
    }

    // ------------------------------------------------------------ waitlist

    private fun seedWaitlist(anchor: LocalDate) {
        val now = clinicTime.now()
        fun entry(name: String, phone: String, code: String, window: PreferredWindow, daysWaiting: Long, practitioner: Practitioner? = null): WaitlistEntry =
            waitlist.save(
                WaitlistEntry(
                    patient = patient(name, phone),
                    treatmentType = byCode.getValue(code),
                    practitioner = practitioner,
                    preferredWindows = setOf(window),
                    createdAt = now - Duration.ofDays(daysWaiting) - Duration.ofHours(2),
                ),
            )

        val vilde = entry("Vilde Sørensen", "+47 924 61 038", "CL", PreferredWindow.WEEKDAY_MORNINGS, 3)
        val anders = entry("Anders Kolstad", "+47 986 22 417", "FI", PreferredWindow.AFTER_16, 4)
        entry("Ingrid Bakken", "+47 913 44 208", "WH", PreferredWindow.WEEKDAY_AFTERNOONS, 5)
        val omar = entry("Omar Hussain", "+47 476 21 903", "CU", PreferredWindow.ANY_THIS_WEEK, 6)
        entry("Thea Bjørnstad", "+47 469 15 882", "RC", PreferredWindow.FRIDAYS_ONLY, 8)
        entry("Mikkel Årnes", "+47 903 74 512", "CL", PreferredWindow.WEEKDAY_MORNINGS, 11)

        // Vilde: a live offer for the first free morning cleaning slot, expiring in 12:34 (the countdown on the dashboard).
        firstFreeSlot(vilde, from = anchor.plusDays(1))?.let { (practitioner, start) ->
            val offer = offers.save(SlotOffer(
                waitlistEntry = vilde, practitioner = practitioner,
                startTime = start, endTime = start + vilde.treatmentType.duration,
                expiresAt = now + Duration.ofMinutes(12) + Duration.ofSeconds(34), createdAt = now - Duration.ofMinutes(2).minusSeconds(26),
            ))
            vilde.markOffered(now)
            events.publishEvent(queue.event(WaitlistEventType.OFFER_SENT, queue.view(vilde), offer.createdAt))
        }

        // "This morning" for the answered offers below; before 11:10 the clock has not got there yet, so use yesterday.
        val morning = if (clinicTime.nowLocal().toLocalTime().isBefore(LocalTime.of(11, 10))) anchor.minusDays(1) else anchor

        // Anders: declined an offer this morning at 11:04 and keeps his place.
        firstFreeSlot(anders, from = anchor.plusDays(1))?.let { (practitioner, start) ->
            val sent = clinicTime.toInstant(morning.atTime(10, 51)) // expires 11:06, declined 11:04
            val offer = offers.save(SlotOffer(
                waitlistEntry = anders, practitioner = practitioner,
                startTime = start, endTime = start + anders.treatmentType.duration,
                expiresAt = sent + Duration.ofMinutes(15), createdAt = sent,
            ))
            anders.markOffered(sent)
            offer.decline(clinicTime.toInstant(morning.atTime(11, 4)))
            anders.decline(clinicTime.toInstant(morning.atTime(11, 4)))
        }

        // Omar: accepted an offer at 09:52 today and has a confirmed appointment ("Open booking").
        firstFreeSlot(omar, from = anchor.plusDays(1))?.let { (practitioner, start) ->
            val sent = clinicTime.toInstant(morning.atTime(9, 40))
            val accepted = clinicTime.toInstant(morning.atTime(9, 52))
            val offer = offers.save(SlotOffer(
                waitlistEntry = omar, practitioner = practitioner,
                startTime = start, endTime = start + omar.treatmentType.duration,
                expiresAt = sent + Duration.ofMinutes(15), createdAt = sent,
            ))
            omar.markOffered(sent)
            val appointment = appointments.save(
                Appointment.fromAcceptedOffer(appointmentService.nextReference(), practitioner, omar.treatmentType, omar.patient, start, accepted),
            )
            offer.accept(accepted)
            omar.accept(appointment, accepted)
            events.publishEvent(AppointmentEvent(AppointmentEventType.BOOKED, accepted, AppointmentSnapshot.of(appointment)))
        }
    }

    /** First free slot for the entry's treatment and preferences within a week of [from], as (practitioner, start). */
    private fun firstFreeSlot(entry: WaitlistEntry, from: LocalDate): Pair<Practitioner, Instant>? {
        val scope = entry.practitioner?.let(::listOf) ?: listOf(nordvik, saether, lund)
        val today = clinicTime.today()
        val slot = availability.availabilityFor(scope, entry.treatmentType.duration, from, from.plusDays(7))
            .flatMap { it.slots }
            .firstOrNull { entry.wantsSlot(it.start, it.practitionerId, today) || entry.preferredWindows == setOf(PreferredWindow.ANY_THIS_WEEK) }
            ?: return null
        val practitioner = scope.first { it.id == slot.practitionerId }
        return practitioner to clinicTime.toInstant(slot.start)
    }

    // ------------------------------------------------------------- helpers

    private fun createAppointment(
        practitioner: Practitioner,
        date: LocalDate,
        time: String,
        patient: Patient,
        treatment: TreatmentType,
        status: AppointmentStatus,
        publish: Boolean,
    ): Appointment {
        val start = clinicTime.toInstant(date.atTime(LocalTime.parse(time)))
        val bookedAt = minOf(start - Duration.ofDays(6) + Duration.ofHours(2), clinicTime.now() - Duration.ofMinutes(5))
        val appointment = Appointment.book(appointmentService.nextReference(), practitioner, treatment, patient, start, bookedAt)
        if (publish) events.publishEvent(AppointmentEvent(AppointmentEventType.BOOKED, bookedAt, AppointmentSnapshot.of(appointment)))

        val confirmedAt = minOf(bookedAt + Duration.ofHours(19), clinicTime.now() - Duration.ofMinutes(4))
        val actedAt = when (status) {
            AppointmentStatus.COMPLETED, AppointmentStatus.NO_SHOW -> start + treatment.duration + Duration.ofMinutes(3)
            AppointmentStatus.CANCELLED -> start - Duration.ofHours(26) // "free cancellation up to 24 hours before"
            else -> confirmedAt + Duration.ofHours(1)
        }
        when (status) {
            AppointmentStatus.REQUESTED -> Unit
            AppointmentStatus.CONFIRMED -> confirm(appointment, confirmedAt, publish)
            AppointmentStatus.COMPLETED -> { confirm(appointment, confirmedAt, publish); appointment.complete(actedAt, FRONT_DESK) }
            AppointmentStatus.NO_SHOW -> { confirm(appointment, confirmedAt, publish); appointment.markNoShow(actedAt, FRONT_DESK) }
            AppointmentStatus.CANCELLED -> { confirm(appointment, confirmedAt, publish); appointment.cancel(actedAt, "patient") }
        }
        return appointments.save(appointment)
    }

    private fun confirm(appointment: Appointment, at: Instant, publish: Boolean) {
        appointment.confirm(at, FRONT_DESK)
        if (publish) events.publishEvent(AppointmentEvent(AppointmentEventType.CONFIRMED, at, AppointmentSnapshot.of(appointment)))
    }

    private fun patient(name: String, phone: String = "+47 900 00 000", email: String? = null): Patient {
        val slug = name.lowercase().replace("ø", "o").replace("æ", "ae").replace("å", "a").replace(Regex("[^a-z ]"), "").trim().replace(' ', '.')
        return Patient(name, phone, email ?: "$slug@example.com")
    }

    private companion object {
        const val FRONT_DESK = "front desk"
        val NORDVIK: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val SAETHER: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val LUND: UUID = UUID.fromString("20000000-0000-0000-0000-000000000003")
        val FULL_DAY_NAMES = listOf(
            "Elias Rud", "Tone Sand", "Håkon Grøn", "Yasmin Ali", "Trygve Foss", "Selma Haug", "Oskar Lien", "Emma Dale",
            "Noah Vang", "Leah Moe", "Aksel Berg", "Ella Strand", "Filip Nes", "Maja Rist", "Liam Torp", "Sofie Aas",
            "Jakob Hoel", "Olivia Bru", "Lucas Vik", "Nora Lund", "William Eng", "Amalie Tveit", "Isak Rø", "Ada Myhre",
        )
    }
}
