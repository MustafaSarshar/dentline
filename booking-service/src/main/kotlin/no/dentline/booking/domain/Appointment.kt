package no.dentline.booking.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Appointment lifecycle:
 *
 * ```
 * REQUESTED ──► CONFIRMED ──► COMPLETED
 *     │             │
 *     ├──► CANCELLED ◄──┤
 *     └──► NO_SHOW   ◄──┘
 * ```
 *
 * `COMPLETED`, `CANCELLED` and `NO_SHOW` are terminal. Anything else is an [IllegalTransitionException].
 */
enum class AppointmentStatus {
    REQUESTED,
    CONFIRMED,
    COMPLETED,
    CANCELLED,
    NO_SHOW;

    /** Legal next states; empty for terminal states. */
    val transitions: Set<AppointmentStatus>
        get() = when (this) {
            REQUESTED -> setOf(CONFIRMED, CANCELLED, NO_SHOW)
            CONFIRMED -> setOf(COMPLETED, CANCELLED, NO_SHOW)
            COMPLETED, CANCELLED, NO_SHOW -> emptySet()
        }

    val isTerminal: Boolean get() = transitions.isEmpty()

    fun canTransitionTo(next: AppointmentStatus): Boolean = next in transitions

    /** Whether an appointment in this state occupies its practitioner's calendar. */
    val blocksCalendar: Boolean get() = this == REQUESTED || this == CONFIRMED

    /** Actions the API exposes from this state; the staff drawer disables the rest. */
    val allowedActions: List<AppointmentAction> get() = AppointmentAction.entries.filter { it.isAllowedFrom(this) }
}

/** The verbs on `/api/appointments/{id}/…`. */
enum class AppointmentAction(val apiName: String, private val target: AppointmentStatus?) {
    CONFIRM("confirm", AppointmentStatus.CONFIRMED),
    COMPLETE("complete", AppointmentStatus.COMPLETED),
    NO_SHOW("no-show", AppointmentStatus.NO_SHOW),
    CANCEL("cancel", AppointmentStatus.CANCELLED),
    RESCHEDULE("reschedule", null);

    fun isAllowedFrom(status: AppointmentStatus): Boolean =
        if (target == null) status.blocksCalendar else status.canTransitionTo(target)
}

enum class HistoryType { BOOKED, REMINDER_SCHEDULED, CONFIRMED, RESCHEDULED, CANCELLED, COMPLETED, NO_SHOW }

@Entity
@Table(name = "appointment")
class Appointment(
    @Id
    val id: UUID = UUID.randomUUID(),
    /** Human-readable id shown to patients and staff, e.g. `DL-4821`. */
    @Column(nullable = false, unique = true)
    val reference: String,
    practitioner: Practitioner,
    // Practitioner and treatment are always rendered with the appointment, so they load eagerly.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "treatment_type_id", nullable = false)
    val treatmentType: TreatmentType,
    @Embedded
    val patient: Patient,
    startTime: Instant,
    status: AppointmentStatus,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "practitioner_id", nullable = false)
    var practitioner: Practitioner = practitioner
        protected set

    @Column(name = "start_time", nullable = false)
    var startTime: Instant = startTime
        protected set

    /** Always `startTime + treatmentType.duration`; recomputed on reschedule. */
    @Column(name = "end_time", nullable = false)
    var endTime: Instant = startTime + treatmentType.duration
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AppointmentStatus = status
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Column(name = "reminder_sent_at")
    var reminderSentAt: Instant? = null
        protected set

    @OneToMany(mappedBy = "appointment", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("occurredAt asc")
    private val _history: MutableList<AppointmentHistoryEntry> = mutableListOf()

    val history: List<AppointmentHistoryEntry> get() = _history.toList()
    val duration: Duration get() = Duration.between(startTime, endTime)
    val allowedActions: List<AppointmentAction> get() = status.allowedActions

    fun confirm(now: Instant, actor: String) =
        transition(AppointmentStatus.CONFIRMED, now, HistoryType.CONFIRMED, "Confirmed by $actor")

    fun complete(now: Instant, actor: String) =
        transition(AppointmentStatus.COMPLETED, now, HistoryType.COMPLETED, "Completed by $actor")

    fun markNoShow(now: Instant, actor: String) =
        transition(AppointmentStatus.NO_SHOW, now, HistoryType.NO_SHOW, "Marked as no-show by $actor")

    fun cancel(now: Instant, actor: String) =
        transition(AppointmentStatus.CANCELLED, now, HistoryType.CANCELLED, "Cancelled by $actor")

    /**
     * Moves the appointment to a new start (and optionally practitioner) without changing status.
     * The caller is responsible for checking that the new window is free under the practitioner lock.
     */
    fun reschedule(newStart: Instant, newPractitioner: Practitioner, now: Instant, actor: String) {
        if (!AppointmentAction.RESCHEDULE.isAllowedFrom(status)) {
            throw DomainConflictException(ConflictCode.ILLEGAL_TRANSITION, "Cannot reschedule a $status appointment")
        }
        practitioner = newPractitioner
        startTime = newStart
        endTime = newStart + treatmentType.duration
        // Any reminder already sent describes the old time, so the appointment becomes due for a
        // new one. Worst case the patient gets a second reminder; the alternative is none at all.
        reminderSentAt = null
        touch(now)
        record(now, HistoryType.RESCHEDULED, "Rescheduled by $actor")
    }

    fun markReminderSent(now: Instant) {
        reminderSentAt = now
    }

    fun record(at: Instant, type: HistoryType, description: String) {
        _history += AppointmentHistoryEntry(appointment = this, occurredAt = at, type = type, description = description)
    }

    private fun transition(next: AppointmentStatus, now: Instant, type: HistoryType, description: String) {
        if (!status.canTransitionTo(next)) throw IllegalTransitionException(status, next)
        status = next
        touch(now)
        record(now, type, description)
    }

    private fun touch(now: Instant) {
        updatedAt = now
    }

    companion object {
        /** An online booking: starts as REQUESTED until the front desk confirms it. */
        fun book(
            reference: String,
            practitioner: Practitioner,
            treatmentType: TreatmentType,
            patient: Patient,
            startTime: Instant,
            now: Instant,
        ): Appointment {
            val appointment = Appointment(
                reference = reference,
                practitioner = practitioner,
                treatmentType = treatmentType,
                patient = patient,
                startTime = startTime,
                status = AppointmentStatus.REQUESTED,
                createdAt = now,
            )
            appointment.record(now, HistoryType.BOOKED, "Requested online by ${patient.firstName}")
            appointment.record(now, HistoryType.REMINDER_SCHEDULED, "Reminder scheduled for the day before")
            return appointment
        }

        /** A booking created by accepting a waitlist offer: the patient already committed, so it is CONFIRMED. */
        fun fromAcceptedOffer(
            reference: String,
            practitioner: Practitioner,
            treatmentType: TreatmentType,
            patient: Patient,
            startTime: Instant,
            now: Instant,
        ): Appointment {
            val appointment = Appointment(
                reference = reference,
                practitioner = practitioner,
                treatmentType = treatmentType,
                patient = patient,
                startTime = startTime,
                status = AppointmentStatus.CONFIRMED,
                createdAt = now,
            )
            appointment.record(now, HistoryType.BOOKED, "Booked from a waitlist offer by ${patient.firstName}")
            appointment.record(now, HistoryType.CONFIRMED, "Confirmed by accepting the offer")
            appointment.record(now, HistoryType.REMINDER_SCHEDULED, "Reminder scheduled for the day before")
            return appointment
        }
    }
}

@Entity
@Table(name = "appointment_history")
class AppointmentHistoryEntry(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    val appointment: Appointment,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: HistoryType,
    @Column(nullable = false)
    val description: String,
)
