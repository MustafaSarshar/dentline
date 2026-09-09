package no.dentline.booking.domain

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID

enum class WaitlistStatus {
    WAITING,
    OFFERED,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    REMOVED;

    /** Can receive the next offer. Declined and expired entries keep their place in the queue. */
    val isQueueEligible: Boolean get() = this == WAITING || this == DECLINED || this == EXPIRED

    /** Counts towards queue position and size. */
    val isInQueue: Boolean get() = isQueueEligible || this == OFFERED
}

/** The pills on the "Join the waitlist" screen. */
enum class PreferredWindow {
    WEEKDAY_MORNINGS,
    WEEKDAY_AFTERNOONS,
    AFTER_16,
    ANY_THIS_WEEK,
    FRIDAYS_ONLY;

    /** Whether a slot starting at [start] (clinic-local time) satisfies this window, judged on [today]. */
    fun matches(start: LocalDateTime, today: LocalDate): Boolean {
        val time = start.toLocalTime()
        val weekday = start.dayOfWeek != DayOfWeek.SATURDAY && start.dayOfWeek != DayOfWeek.SUNDAY
        return when (this) {
            WEEKDAY_MORNINGS -> weekday && time < NOON
            WEEKDAY_AFTERNOONS -> weekday && time >= NOON && time < FOUR_PM
            AFTER_16 -> time >= FOUR_PM
            ANY_THIS_WEEK -> {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val date = start.toLocalDate()
                !date.isBefore(monday) && date.isBefore(monday.plusWeeks(1))
            }
            FRIDAYS_ONLY -> start.dayOfWeek == DayOfWeek.FRIDAY
        }
    }

    private companion object {
        val NOON: LocalTime = LocalTime.NOON
        val FOUR_PM: LocalTime = LocalTime.of(16, 0)
    }
}

/**
 * A patient waiting for a cancellation. Position in the queue is derived from [createdAt]
 * among in-queue entries for the same treatment; it is never stored.
 */
@Entity
@Table(name = "waitlist_entry")
class WaitlistEntry(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Embedded
    val patient: Patient,
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "treatment_type_id", nullable = false)
    val treatmentType: TreatmentType,
    /** Null means any practitioner. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "practitioner_id")
    val practitioner: Practitioner?,
    preferredWindows: Set<PreferredWindow>,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    init {
        require(preferredWindows.isNotEmpty()) { "At least one preferred window is required" }
    }

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "waitlist_entry_window", joinColumns = [JoinColumn(name = "waitlist_entry_id")])
    @Column(name = "preferred_window", nullable = false)
    @Enumerated(EnumType.STRING)
    private val _preferredWindows: MutableSet<PreferredWindow> = preferredWindows.toMutableSet()

    val preferredWindows: Set<PreferredWindow> get() = _preferredWindows.toSet()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: WaitlistStatus = WaitlistStatus.WAITING
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    /** The appointment created when an offer was accepted. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "appointment_id")
    var appointment: Appointment? = null
        protected set

    /** Whether a slot at [start] with [practitionerId] is something this patient asked for. */
    fun wantsSlot(start: LocalDateTime, practitionerId: UUID, today: LocalDate): Boolean {
        val wanted = practitioner
        val practitionerOk = wanted == null || wanted.id == practitionerId
        return practitionerOk && _preferredWindows.any { it.matches(start, today) }
    }

    fun markOffered(now: Instant) {
        if (status == WaitlistStatus.OFFERED) {
            throw DomainConflictException(ConflictCode.OFFER_ALREADY_PENDING, "This patient already has a pending offer")
        }
        requireEligible()
        status = WaitlistStatus.OFFERED
        touch(now)
    }

    fun accept(appointment: Appointment, now: Instant) {
        requireOffered()
        status = WaitlistStatus.ACCEPTED
        this.appointment = appointment
        touch(now)
    }

    fun decline(now: Instant) {
        requireOffered()
        status = WaitlistStatus.DECLINED
        touch(now)
    }

    fun expire(now: Instant) {
        requireOffered()
        status = WaitlistStatus.EXPIRED
        touch(now)
    }

    /** Staff withdrew the offer: the patient goes back to plain waiting. */
    fun withdrawOffer(now: Instant) {
        requireOffered()
        status = WaitlistStatus.WAITING
        touch(now)
    }

    fun remove(now: Instant) {
        if (status == WaitlistStatus.REMOVED) {
            throw DomainConflictException(ConflictCode.ILLEGAL_TRANSITION, "Waitlist entry is already removed")
        }
        status = WaitlistStatus.REMOVED
        touch(now)
    }

    private fun requireEligible() {
        if (!status.isQueueEligible) {
            throw DomainConflictException(ConflictCode.ILLEGAL_TRANSITION, "Waitlist entry in state $status cannot receive an offer")
        }
    }

    private fun requireOffered() {
        if (status != WaitlistStatus.OFFERED) {
            throw DomainConflictException(ConflictCode.OFFER_NOT_ACTIVE, "Waitlist entry has no pending offer")
        }
    }

    private fun touch(now: Instant) {
        updatedAt = now
    }
}

enum class SlotOfferStatus { PENDING, ACCEPTED, DECLINED, EXPIRED, WITHDRAWN }

/**
 * A time-limited hold on a freed window for one waitlist entry. While PENDING it blocks
 * availability exactly like an appointment would.
 */
@Entity
@Table(name = "slot_offer")
class SlotOffer(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "waitlist_entry_id", nullable = false)
    val waitlistEntry: WaitlistEntry,
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "practitioner_id", nullable = false)
    val practitioner: Practitioner,
    @Column(name = "start_time", nullable = false)
    val startTime: Instant,
    @Column(name = "end_time", nullable = false)
    val endTime: Instant,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    init {
        require(startTime.isBefore(endTime)) { "Offer window must start before it ends" }
        require(expiresAt.isAfter(createdAt)) { "Offer must expire after it is created" }
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SlotOfferStatus = SlotOfferStatus.PENDING
        protected set

    @Column(name = "responded_at")
    var respondedAt: Instant? = null
        protected set

    fun isActive(now: Instant): Boolean = status == SlotOfferStatus.PENDING && now.isBefore(expiresAt)

    fun accept(now: Instant) = close(SlotOfferStatus.ACCEPTED, now, requireUnexpired = true)

    fun decline(now: Instant) = close(SlotOfferStatus.DECLINED, now, requireUnexpired = true)

    fun withdraw(now: Instant) = close(SlotOfferStatus.WITHDRAWN, now, requireUnexpired = false)

    /** Called by the scheduled sweep once [expiresAt] has passed. */
    fun expire(now: Instant) = close(SlotOfferStatus.EXPIRED, now, requireUnexpired = false)

    private fun close(next: SlotOfferStatus, now: Instant, requireUnexpired: Boolean) {
        val active = if (requireUnexpired) isActive(now) else status == SlotOfferStatus.PENDING
        if (!active) throw DomainConflictException(ConflictCode.OFFER_NOT_ACTIVE, "This offer is no longer active")
        status = next
        respondedAt = now
    }
}
