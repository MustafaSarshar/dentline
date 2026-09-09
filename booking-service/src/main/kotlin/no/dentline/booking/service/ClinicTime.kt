package no.dentline.booking.service

import no.dentline.booking.config.DentlineProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * All conversions between instants and clinic-local wall-clock time go through here, so the
 * clinic zone is applied in exactly one place.
 */
@Component
class ClinicTime(private val clock: Clock, properties: DentlineProperties) {
    val zone: ZoneId = properties.zoneId

    fun now(): Instant = clock.instant()
    fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), zone)
    fun nowLocal(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), zone)

    fun toLocal(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, zone)
    fun toInstant(local: LocalDateTime): Instant = local.atZone(zone).toInstant()
    fun toOffset(instant: Instant): OffsetDateTime = instant.atOffset(zone.rules.getOffset(instant))
    fun startOfDay(date: LocalDate): Instant = date.atStartOfDay(zone).toInstant()
}
