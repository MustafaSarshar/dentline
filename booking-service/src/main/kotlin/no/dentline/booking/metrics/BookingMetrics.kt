package no.dentline.booking.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import no.dentline.booking.event.AppointmentEvent
import no.dentline.booking.event.AppointmentEventType
import no.dentline.booking.event.WaitlistEvent
import no.dentline.booking.event.WaitlistEventType
import no.dentline.booking.service.MetricsSummaryService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.atomic.AtomicReference

/**
 * Operational metrics for Prometheus (`/actuator/prometheus`). Counters follow the domain events
 * after commit, so they count what actually happened; the rolling gauges are refreshed once a
 * minute from the same queries the dashboard's stat cards use.
 *
 * - `dentline_appointments_total{event}`   bookings and every transition
 * - `dentline_cancellations_total{by}`     who cancelled
 * - `dentline_waitlist_offers_total{outcome}` sent / accepted / declined / expired / withdrawn
 * - `dentline_no_show_rate`                rolling 30 days, 0–1
 * - `dentline_waitlist_offer_acceptance_rate` accepted ÷ answered (accepted + declined + expired) since start
 * - `dentline_waitlist_queued`             entries currently in a queue
 * - `dentline_booking_latency_seconds`     timer around POST /api/appointments, with histogram buckets
 */
@Component
class BookingMetrics(private val registry: MeterRegistry, private val summary: MetricsSummaryService) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val noShowRate = AtomicReference(0.0)
    private val queued = AtomicReference(0.0)
    private val offersAccepted = AtomicReference(0.0)
    private val offersAnswered = AtomicReference(0.0)

    /** One timer per outcome; Prometheus requires every series of a name to carry the same tag keys. */
    private fun latency(outcome: String): Timer = Timer.builder("dentline.booking.latency")
        .description("Time to handle a booking request, including the practitioner lock")
        .tag("outcome", outcome)
        .publishPercentileHistogram()
        .register(registry)

    init {
        Gauge.builder("dentline.no_show.rate", noShowRate) { it.get() }
            .description("No-shows divided by attended + no-shows, rolling 30 days").register(registry)
        Gauge.builder("dentline.waitlist.queued", queued) { it.get() }
            .description("Waitlist entries currently waiting or offered").register(registry)
        Gauge.builder("dentline.waitlist.offer.acceptance.rate") { acceptanceRate() }
            .description("Accepted offers divided by answered offers since the service started").register(registry)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: AppointmentEvent) {
        if (event.type == AppointmentEventType.REMINDER_DUE) return
        counter("dentline.appointments", "event", event.type.name).increment()
        if (event.type == AppointmentEventType.CANCELLED) {
            counter("dentline.cancellations", "by", event.cancelledBy?.name ?: "UNKNOWN").increment()
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: WaitlistEvent) {
        val outcome = when (event.type) {
            WaitlistEventType.OFFER_SENT -> "SENT"
            WaitlistEventType.OFFER_ACCEPTED -> "ACCEPTED".also { offersAccepted.updateAndGet { it + 1 }; offersAnswered.updateAndGet { it + 1 } }
            WaitlistEventType.OFFER_DECLINED -> "DECLINED".also { offersAnswered.updateAndGet { it + 1 } }
            WaitlistEventType.OFFER_EXPIRED -> "EXPIRED".also { offersAnswered.updateAndGet { it + 1 } }
            WaitlistEventType.OFFER_WITHDRAWN -> "WITHDRAWN"
            WaitlistEventType.ENTRY_CREATED, WaitlistEventType.ENTRY_REMOVED -> return
        }
        counter("dentline.waitlist.offers", "outcome", outcome).increment()
    }

    /** Times one booking attempt; failures (409s) are timed too, tagged by outcome. */
    fun <T> timeBooking(block: () -> T): T {
        val sample = Timer.start(registry)
        var outcome = "created"
        try {
            return block()
        } catch (e: Exception) {
            outcome = "rejected"
            throw e
        } finally {
            sample.stop(latency(outcome))
        }
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 60_000)
    fun refreshGauges() {
        try {
            val s = summary.summary()
            noShowRate.set(s.noShowRate.value)
            queued.set(s.waitlistAvgWaitDays.queued.toDouble())
        } catch (e: Exception) {
            log.warn("Could not refresh gauges: {}", e.message)
        }
    }

    private fun acceptanceRate(): Double {
        val answered = offersAnswered.get()
        return if (answered == 0.0) 0.0 else offersAccepted.get() / answered
    }

    private fun counter(name: String, tagKey: String, tagValue: String): Counter = registry.counter(name, tagKey, tagValue)

    @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent::class)
    fun warmUp() {
        // Register every series up front so dashboards show zeros instead of "no data".
        AppointmentEventType.entries.filter { it != AppointmentEventType.REMINDER_DUE }.forEach { counter("dentline.appointments", "event", it.name) }
        listOf("SENT", "ACCEPTED", "DECLINED", "EXPIRED", "WITHDRAWN").forEach { counter("dentline.waitlist.offers", "outcome", it) }
        listOf("PATIENT", "STAFF").forEach { counter("dentline.cancellations", "by", it) }
        listOf("created", "rejected").forEach { latency(it) }
    }
}
