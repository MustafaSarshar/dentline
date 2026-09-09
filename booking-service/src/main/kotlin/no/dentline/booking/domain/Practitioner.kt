package no.dentline.booking.domain

import jakarta.persistence.AttributeConverter
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

enum class PractitionerTitle { DENTIST, HYGIENIST }

/**
 * A practitioner and their weekly working hours. The practitioner row is also the unit of
 * locking for bookings: `SELECT … FOR UPDATE` on it serialises writes to one calendar.
 */
@Entity
@Table(name = "practitioner")
class Practitioner(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    var name: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var title: PractitionerTitle,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @OneToMany(mappedBy = "practitioner", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("dayOfWeek asc, startTime asc")
    private val _workingHours: MutableList<WorkingHours> = mutableListOf()

    val workingHours: List<WorkingHours> get() = _workingHours.toList()

    fun addWorkingHours(day: DayOfWeek, start: LocalTime, end: LocalTime): Practitioner {
        _workingHours += WorkingHours(practitioner = this, dayOfWeek = day, startTime = start, endTime = end)
        return this
    }

    /** Working windows on a weekday, earliest first. Empty means the practitioner is off that day. */
    fun workingWindowsOn(day: DayOfWeek): List<TimeWindow> =
        _workingHours.filter { it.dayOfWeek == day }.map { it.window }.sortedBy { it.start }
}

@Entity
@Table(name = "working_hours")
class WorkingHours(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "practitioner_id", nullable = false)
    val practitioner: Practitioner,
    @Convert(converter = DayOfWeekConverter::class)
    @Column(name = "day_of_week", nullable = false)
    val dayOfWeek: DayOfWeek,
    @Column(name = "start_time", nullable = false)
    val startTime: LocalTime,
    @Column(name = "end_time", nullable = false)
    val endTime: LocalTime,
) {
    init {
        require(startTime.isBefore(endTime)) { "Working hours must start before they end: $startTime–$endTime" }
    }

    val window: TimeWindow get() = TimeWindow(startTime, endTime)
}

/** Stores [DayOfWeek] as ISO 1–7 in a smallint column. */
@Converter
class DayOfWeekConverter : AttributeConverter<DayOfWeek, Short> {
    override fun convertToDatabaseColumn(attribute: DayOfWeek): Short = attribute.value.toShort()
    override fun convertToEntityAttribute(dbData: Short): DayOfWeek = DayOfWeek.of(dbData.toInt())
}
