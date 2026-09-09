package no.dentline.booking.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * When a patient was last sent a six-month recall. Keyed by normalised email because there is
 * no patient table yet (see README, "Next steps for production").
 */
@Entity
@Table(name = "recall_notice")
class RecallNotice(
    @Id
    @Column(name = "patient_email")
    val patientEmail: String,
    @Column(name = "patient_name", nullable = false)
    var patientName: String,
    @Column(name = "patient_phone", nullable = false)
    var patientPhone: String,
    @Column(name = "sent_at", nullable = false)
    var sentAt: Instant,
)
