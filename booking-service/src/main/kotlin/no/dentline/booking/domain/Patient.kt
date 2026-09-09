package no.dentline.booking.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * Patient contact details as embedded on appointments and waitlist entries.
 * There is no patient table yet; a patient is identified by [normalizedEmail] across records.
 */
@Embeddable
class Patient(
    @Column(name = "patient_name", nullable = false)
    val name: String,
    @Column(name = "patient_phone", nullable = false)
    val phone: String,
    @Column(name = "patient_email", nullable = false)
    val email: String,
) {
    val firstName: String get() = name.trim().substringBefore(' ')
    val normalizedEmail: String get() = email.trim().lowercase()
}
