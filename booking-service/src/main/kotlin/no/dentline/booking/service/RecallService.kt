package no.dentline.booking.service

import no.dentline.booking.domain.NotFoundException
import no.dentline.booking.domain.Patient
import no.dentline.booking.domain.RecallNotice
import no.dentline.booking.event.PatientSnapshot
import no.dentline.booking.event.RecallEvent
import no.dentline.booking.event.RecallEventType
import no.dentline.booking.repository.AppointmentRepository
import no.dentline.booking.repository.RecallNoticeRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.Period

/** One row of the staff "Recalls" table. */
data class RecallRow(
    val patient: Patient,
    val practitionerName: String,
    val lastVisitDate: LocalDate,
    val dueDate: LocalDate,
    val recallSentAt: Instant?,
)

/**
 * Six-month check-up recalls. A patient is due six months after their last COMPLETED check-up,
 * appears in the list from one month before that, and drops out once a new check-up is booked.
 */
@Service
class RecallService(
    private val appointments: AppointmentRepository,
    private val notices: RecallNoticeRepository,
    private val clinicTime: ClinicTime,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun dueRecalls(): List<RecallRow> {
        val today = clinicTime.today()
        val alreadyBooked = appointments.findUpcomingByTreatmentCode(CHECK_UP, clinicTime.now())
            .map { it.patient.normalizedEmail }
            .toSet()
        val lastVisitByPatient = appointments.findCompletedByTreatmentCode(CHECK_UP) // newest first
            .distinctBy { it.patient.normalizedEmail }
        val sentAt = notices.findAllById(lastVisitByPatient.map { it.patient.normalizedEmail }).associateBy { it.patientEmail }

        return lastVisitByPatient
            .filter { it.patient.normalizedEmail !in alreadyBooked }
            .map { last ->
                val lastVisit = clinicTime.toLocal(last.startTime).toLocalDate()
                RecallRow(
                    patient = last.patient,
                    practitionerName = last.practitioner.name,
                    lastVisitDate = lastVisit,
                    dueDate = lastVisit + RECALL_INTERVAL,
                    recallSentAt = sentAt[last.patient.normalizedEmail]?.sentAt,
                )
            }
            .filter { !it.dueDate.minus(LISTED_AHEAD).isAfter(today) }
            .sortedBy { it.dueDate }
    }

    /** Front desk "Send recall" / "Resend". */
    @Transactional
    fun send(email: String): RecallRow {
        val row = dueRecalls().firstOrNull { it.patient.normalizedEmail == email.trim().lowercase() }
            ?: throw NotFoundException("Recall for patient", email)
        return publish(row)
    }

    /**
     * The daily 06:00 job: publish RECALL_DUE for every overdue patient not reminded in the last
     * [RESEND_AFTER]. Returns how many were sent.
     */
    @Transactional
    fun sendDueRecalls(): Int {
        val today = clinicTime.today()
        val cutoff = clinicTime.now() - RESEND_AFTER
        val due = dueRecalls().filter { !it.dueDate.isAfter(today) && (it.recallSentAt == null || it.recallSentAt.isBefore(cutoff)) }
        due.forEach(::publish)
        log.info("Recall job: {} recall(s) published", due.size)
        return due.size
    }

    private fun publish(row: RecallRow): RecallRow {
        val now = clinicTime.now()
        val email = row.patient.normalizedEmail
        val notice = notices.findById(email).orElse(null)?.apply { sentAt = now; patientName = row.patient.name; patientPhone = row.patient.phone }
            ?: RecallNotice(patientEmail = email, patientName = row.patient.name, patientPhone = row.patient.phone, sentAt = now)
        notices.save(notice)
        events.publishEvent(
            RecallEvent(
                type = RecallEventType.RECALL_DUE,
                occurredAt = now,
                patient = PatientSnapshot(row.patient.name, row.patient.phone, row.patient.email),
                lastVisitDate = row.lastVisitDate,
                dueDate = row.dueDate,
                practitionerName = row.practitionerName,
            ),
        )
        return row.copy(recallSentAt = now)
    }

    private companion object {
        const val CHECK_UP = "CU"
        val RECALL_INTERVAL: Period = Period.ofMonths(6)
        val LISTED_AHEAD: Period = Period.ofMonths(1)
        val RESEND_AFTER: java.time.Duration = java.time.Duration.ofDays(30)
    }
}
