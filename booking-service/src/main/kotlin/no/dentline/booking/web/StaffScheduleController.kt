package no.dentline.booking.web

import no.dentline.booking.service.ScheduleService
import no.dentline.booking.web.dto.ApiMapper
import no.dentline.booking.web.dto.DayScheduleResponse
import no.dentline.booking.web.dto.WeekScheduleResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** Staff calendar. `?date=` gives the per-practitioner day; `?from=&to=` the week overview. */
@RestController
@RequestMapping("/api/staff/schedule")
class StaffScheduleController(private val schedule: ScheduleService, private val mapper: ApiMapper) {

    @GetMapping(params = ["date"])
    fun day(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate): DayScheduleResponse =
        mapper.daySchedule(schedule.day(date))

    @GetMapping(params = ["from", "to"])
    fun range(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): WeekScheduleResponse = mapper.weekSchedule(schedule.range(from, to))
}
