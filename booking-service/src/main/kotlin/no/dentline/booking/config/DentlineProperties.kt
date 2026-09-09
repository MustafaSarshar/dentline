package no.dentline.booking.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneId

/**
 * Clinic-level settings. Everything the prototypes exposed as "tweaks" (slot step, offer hold)
 * lives here so it is configurable per environment and reported to the frontend via `GET /api/config`.
 */
@ConfigurationProperties(prefix = "dentline")
data class DentlineProperties(
    val timezone: String = "Europe/Oslo",
    val clinic: Clinic = Clinic(),
    val availability: Availability = Availability(),
    val waitlist: Waitlist = Waitlist(),
    val cors: Cors = Cors(),
    val seed: Seed = Seed(),
) {
    val zoneId: ZoneId get() = ZoneId.of(timezone)

    data class Clinic(
        val name: String = "Dentline Majorstuen",
        val addressLine1: String = "Kirkeveien 64B",
        val addressLine2: String = "0364 Oslo",
    )

    data class Availability(
        /** Granularity of offered start times. The prototype exposed 15 or 30. */
        val slotStepMinutes: Int = 15,
        /** How far ahead a single availability query may look. */
        val maxRangeDays: Int = 31,
    ) {
        val slotStep: Duration get() = Duration.ofMinutes(slotStepMinutes.toLong())
    }

    data class Waitlist(
        /** How long a freed slot is held for the patient it is offered to. */
        val offerHoldMinutes: Int = 15,
        /** How far ahead the staff "Send offer" action searches for a matching free slot. */
        val manualOfferSearchDays: Int = 14,
    ) {
        val offerHold: Duration get() = Duration.ofMinutes(offerHoldMinutes.toLong())
    }

    data class Cors(val allowedOrigins: List<String> = listOf("http://localhost:5173"))

    data class Seed(
        /** When true, demo data relative to today is created on startup if the database has no appointments. */
        val enabled: Boolean = false,
    )
}
