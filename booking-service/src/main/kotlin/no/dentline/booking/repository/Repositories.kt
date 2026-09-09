package no.dentline.booking.repository

import no.dentline.booking.domain.Appointment
import no.dentline.booking.domain.AppointmentStatus
import no.dentline.booking.domain.Practitioner
import no.dentline.booking.domain.RecallNotice
import no.dentline.booking.domain.SlotOffer
import no.dentline.booking.domain.TreatmentType
import no.dentline.booking.domain.WaitlistEntry
import no.dentline.booking.domain.WaitlistStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface TreatmentTypeRepository : JpaRepository<TreatmentType, UUID> {
    fun findAllByOrderByDurationMinutesAscNameAsc(): List<TreatmentType>
}

interface PractitionerRepository : JpaRepository<Practitioner, UUID> {
    fun findAllByOrderByName(): List<Practitioner>

    /**
     * The booking lock. Takes a row lock on one practitioner for the rest of the transaction, so
     * every write to that practitioner's calendar (book, reschedule, accept offer) is serialised
     * while other practitioners' calendars stay fully concurrent. Any second transaction that
     * wants the same practitioner blocks here until the first commits, then re-reads and sees
     * the new appointment in its overlap check.
     */
    @Query(value = "select * from practitioner where id = :id for update", nativeQuery = true)
    fun lockForUpdate(@Param("id") id: UUID): Practitioner?
}

interface AppointmentRepository : JpaRepository<Appointment, UUID> {

    @Query(value = "select * from appointment where id = :id for update", nativeQuery = true)
    fun lockForUpdate(@Param("id") id: UUID): Appointment?

    /** Appointments on one practitioner's calendar that overlap [start, end) and still occupy it. */
    @Query(
        """
        select a from Appointment a
        where a.practitioner.id = :practitionerId
          and a.status in :statuses
          and a.startTime < :end
          and a.endTime > :start
        """,
    )
    fun findOverlapping(
        @Param("practitionerId") practitionerId: UUID,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
        @Param("statuses") statuses: Collection<AppointmentStatus> = BLOCKING,
    ): List<Appointment>

    @Query(
        """
        select a from Appointment a
        where a.startTime >= :from and a.startTime < :to
        order by a.startTime asc, a.practitioner.name asc
        """,
    )
    fun findAllStartingBetween(@Param("from") from: Instant, @Param("to") to: Instant): List<Appointment>

    @Query(value = "select nextval('appointment_reference_seq')", nativeQuery = true)
    fun nextReferenceNumber(): Long

    /** Confirmed appointments in [from, to) that have not had their reminder yet. */
    @Query(
        """
        select a from Appointment a
        where a.status = no.dentline.booking.domain.AppointmentStatus.CONFIRMED
          and a.reminderSentAt is null
          and a.startTime >= :from and a.startTime < :to
        order by a.startTime asc
        """,
    )
    fun findConfirmedNeedingReminder(@Param("from") from: Instant, @Param("to") to: Instant): List<Appointment>

    /** Every completed appointment of one treatment, newest first (recalls derive "last visit" from it). */
    @Query(
        """
        select a from Appointment a
        where a.status = no.dentline.booking.domain.AppointmentStatus.COMPLETED and a.treatmentType.code = :code
        order by a.startTime desc
        """,
    )
    fun findCompletedByTreatmentCode(@Param("code") code: String): List<Appointment>

    /** Future appointments of one treatment that still occupy the calendar. */
    @Query(
        """
        select a from Appointment a
        where a.status in :statuses and a.treatmentType.code = :code and a.startTime > :after
        """,
    )
    fun findUpcomingByTreatmentCode(
        @Param("code") code: String,
        @Param("after") after: Instant,
        @Param("statuses") statuses: Collection<AppointmentStatus> = BLOCKING,
    ): List<Appointment>

    // --- metrics ---

    @Query("select count(a) from Appointment a where a.startTime >= :from and a.startTime < :to")
    fun countStartingBetween(@Param("from") from: Instant, @Param("to") to: Instant): Long

    @Query("select count(a) from Appointment a where a.status = :status and a.startTime >= :from and a.startTime < :to")
    fun countByStatusStartingBetween(@Param("status") status: AppointmentStatus, @Param("from") from: Instant, @Param("to") to: Instant): Long

    /** Terminal states are reached once, so `updatedAt` is when the cancellation happened. */
    @Query("select count(a) from Appointment a where a.status = :status and a.updatedAt >= :from and a.updatedAt < :to")
    fun countByStatusUpdatedBetween(@Param("status") status: AppointmentStatus, @Param("from") from: Instant, @Param("to") to: Instant): Long

    companion object {
        val BLOCKING: Set<AppointmentStatus> = AppointmentStatus.entries.filter { it.blocksCalendar }.toSet()
    }
}

interface RecallNoticeRepository : JpaRepository<RecallNotice, String>


interface WaitlistEntryRepository : JpaRepository<WaitlistEntry, UUID> {

    @Query(value = "select * from waitlist_entry where id = :id for update", nativeQuery = true)
    fun lockForUpdate(@Param("id") id: UUID): WaitlistEntry?

    /** Entries in the given states, oldest first: the order offers are handed out in. */
    @Query("select e from WaitlistEntry e where e.status in :statuses order by e.createdAt asc, e.id asc")
    fun findByStatusInOrderByCreatedAt(@Param("statuses") statuses: Collection<WaitlistStatus>): List<WaitlistEntry>

    /** The queue for one treatment; position = index + 1. */
    @Query(
        """
        select e from WaitlistEntry e
        where e.treatmentType.id = :treatmentTypeId and e.status in :statuses
        order by e.createdAt asc, e.id asc
        """,
    )
    fun findQueue(
        @Param("treatmentTypeId") treatmentTypeId: UUID,
        @Param("statuses") statuses: Collection<WaitlistStatus>,
    ): List<WaitlistEntry>

    /** The staff table: everyone still relevant, with recently accepted entries kept for context. */
    @Query(
        """
        select e from WaitlistEntry e
        where e.status <> no.dentline.booking.domain.WaitlistStatus.REMOVED
          and (e.status <> no.dentline.booking.domain.WaitlistStatus.ACCEPTED or e.updatedAt >= :acceptedSince)
        order by e.createdAt asc, e.id asc
        """,
    )
    fun findForStaff(@Param("acceptedSince") acceptedSince: Instant): List<WaitlistEntry>

    fun countByStatusIn(statuses: Collection<WaitlistStatus>): Long
}

interface SlotOfferRepository : JpaRepository<SlotOffer, UUID> {

    /** Pending offers hold a window exactly like an appointment does. */
    @Query(
        """
        select o from SlotOffer o
        where o.practitioner.id = :practitionerId
          and o.status = no.dentline.booking.domain.SlotOfferStatus.PENDING
          and o.startTime < :end
          and o.endTime > :start
        """,
    )
    fun findPendingOverlapping(
        @Param("practitionerId") practitionerId: UUID,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<SlotOffer>

    @Query(
        """
        select o from SlotOffer o
        where o.waitlistEntry.id = :entryId and o.status = no.dentline.booking.domain.SlotOfferStatus.PENDING
        """,
    )
    fun findPendingForEntry(@Param("entryId") entryId: UUID): SlotOffer?

    @Query("select o from SlotOffer o where o.waitlistEntry.id = :entryId order by o.createdAt desc, o.id desc limit 1")
    fun findLatestForEntry(@Param("entryId") entryId: UUID): SlotOffer?

    /** Whether this patient has already been offered exactly this slot (declined/expired/withdrawn). */
    @Query(
        """
        select count(o) > 0 from SlotOffer o
        where o.waitlistEntry.id = :entryId and o.practitioner.id = :practitionerId and o.startTime = :startTime
        """,
    )
    fun wasAlreadyOffered(
        @Param("entryId") entryId: UUID,
        @Param("practitionerId") practitionerId: UUID,
        @Param("startTime") startTime: Instant,
    ): Boolean

    @Query(
        """
        select o from SlotOffer o
        where o.status = no.dentline.booking.domain.SlotOfferStatus.PENDING and o.expiresAt <= :now
        order by o.expiresAt asc
        """,
    )
    fun findExpired(@Param("now") now: Instant): List<SlotOffer>

    @Query(
        """
        select o from SlotOffer o join fetch o.waitlistEntry
        where o.status = :status and o.respondedAt >= :from and o.respondedAt < :to
        """,
    )
    fun findByStatusRespondedBetween(
        @Param("status") status: no.dentline.booking.domain.SlotOfferStatus,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<SlotOffer>
}
