package no.dentline.booking.web

import no.dentline.booking.service.MetricsSummary
import no.dentline.booking.service.MetricsSummaryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Feeds the dashboard's four stat cards. */
@RestController
@RequestMapping("/api/staff/metrics")
class StaffMetricsController(private val metrics: MetricsSummaryService) {

    @GetMapping("/summary")
    fun summary(): MetricsSummary = metrics.summary()
}
