package no.dentline.booking.event

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Types on the `recall-events` topic. */
enum class RecallEventType { RECALL_DUE }

/** Published by the daily 06:00 recall job and by the front desk's manual "Send recall". Keyed by patient email. */
data class RecallEvent(
    val type: RecallEventType,
    val occurredAt: Instant,
    val patient: PatientSnapshot,
    val lastVisitDate: LocalDate,
    val dueDate: LocalDate,
    val practitionerName: String,
    val eventId: UUID = UUID.randomUUID(),
)
