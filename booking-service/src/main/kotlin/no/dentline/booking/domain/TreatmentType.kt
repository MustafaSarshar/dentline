package no.dentline.booking.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

@Entity
@Table(name = "treatment_type")
class TreatmentType(
    @Id
    val id: UUID = UUID.randomUUID(),
    /** Two-letter code shown on the treatment card (CU, CL, FI, RC, WH). */
    @Column(nullable = false, unique = true)
    val code: String,
    @Column(nullable = false, unique = true)
    val name: String,
    @Column(name = "duration_minutes", nullable = false)
    val durationMinutes: Int,
    @Column(name = "price_nok", nullable = false, precision = 10, scale = 2)
    val priceNok: BigDecimal,
) {
    init {
        require(durationMinutes > 0) { "Treatment duration must be positive" }
    }

    val duration: Duration get() = Duration.ofMinutes(durationMinutes.toLong())
}
