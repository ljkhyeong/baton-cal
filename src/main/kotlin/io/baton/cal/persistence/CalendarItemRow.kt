package io.baton.cal.persistence

import io.baton.cal.calendar.CalendarItem
import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.snapshot.ScheduleSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * `calendar_item` 행이다. 시간 형태마다 사용하는 열이 다르므로 [ScheduleWindow]와의 변환을 이 파일에서만 한다.
 * 새 시간 형태를 추가하면 저장([from])과 복원([toCalendarItem])을 함께 고치고 `ck_calendar_item_time_shape`를
 * 마이그레이션에서 함께 갱신한다.
 */
data class CalendarItemRow(
    val sourceItemId: UUID,
    val seasonId: UUID,
    val revision: Int,
    val status: CalendarItemStatus,
    val summary: String,
    val description: String?,
    val location: String?,
    val timeType: ScheduleTimeType,
    val startsAtInstant: Instant?,
    val endsAtInstant: Instant?,
    val startsAtLocal: LocalDateTime?,
    val endsAtLocal: LocalDateTime?,
    val zoneId: String?,
    val startsOnDate: LocalDate?,
    val endsOnDate: LocalDate?,
    val sourceUpdatedAt: Instant,
    val acceptedAt: Instant,
) {
    fun toCalendarItem(): CalendarItem = CalendarItem(
        sourceItemId = sourceItemId,
        revision = revision,
        status = status,
        summary = summary,
        description = description,
        location = location,
        schedule = when (timeType) {
            ScheduleTimeType.UTC_INSTANT -> ScheduleWindow.UtcInstant(
                start = requireNotNull(startsAtInstant),
                end = requireNotNull(endsAtInstant),
            )

            ScheduleTimeType.UTC_POINT -> ScheduleWindow.UtcPoint(
                at = requireNotNull(startsAtInstant),
            )

            ScheduleTimeType.ZONED_LOCAL -> ScheduleWindow.ZonedLocal(
                start = requireNotNull(startsAtLocal),
                end = requireNotNull(endsAtLocal),
                zoneId = requireNotNull(zoneId),
            )

            ScheduleTimeType.ZONED_LOCAL_POINT -> ScheduleWindow.ZonedLocalPoint(
                at = requireNotNull(startsAtLocal),
                zoneId = requireNotNull(zoneId),
            )

            ScheduleTimeType.ALL_DAY -> ScheduleWindow.AllDay(
                startDate = requireNotNull(startsOnDate),
                endDate = requireNotNull(endsOnDate),
            )
        },
        sourceUpdatedAt = sourceUpdatedAt,
        acceptedAt = acceptedAt,
    )

    companion object {
        fun from(snapshot: ScheduleSnapshot, acceptedAt: Instant): CalendarItemRow {
            val columns = snapshot.schedule.toColumns()
            return CalendarItemRow(
                sourceItemId = snapshot.sourceItemId,
                seasonId = snapshot.seasonId,
                revision = snapshot.revision,
                status = snapshot.status,
                summary = snapshot.summary,
                description = snapshot.description,
                location = snapshot.location,
                timeType = columns.timeType,
                startsAtInstant = columns.startsAtInstant,
                endsAtInstant = columns.endsAtInstant,
                startsAtLocal = columns.startsAtLocal,
                endsAtLocal = columns.endsAtLocal,
                zoneId = columns.zoneId,
                startsOnDate = columns.startsOnDate,
                endsOnDate = columns.endsOnDate,
                sourceUpdatedAt = snapshot.sourceUpdatedAt,
                acceptedAt = acceptedAt,
            )
        }
    }
}

/** `calendar_item.time_type` 열의 값이다. 도메인의 시간 형태는 [ScheduleWindow]로 표현한다. */
enum class ScheduleTimeType {
    UTC_INSTANT,
    UTC_POINT,
    ZONED_LOCAL,
    ZONED_LOCAL_POINT,
    ALL_DAY,
}

private fun ScheduleWindow.toColumns(): ScheduleColumns = when (this) {
    is ScheduleWindow.UtcInstant -> ScheduleColumns(
        timeType = ScheduleTimeType.UTC_INSTANT,
        startsAtInstant = start,
        endsAtInstant = end,
    )

    is ScheduleWindow.UtcPoint -> ScheduleColumns(
        timeType = ScheduleTimeType.UTC_POINT,
        startsAtInstant = at,
    )

    is ScheduleWindow.ZonedLocal -> ScheduleColumns(
        timeType = ScheduleTimeType.ZONED_LOCAL,
        startsAtLocal = start,
        endsAtLocal = end,
        zoneId = zoneId,
    )

    is ScheduleWindow.ZonedLocalPoint -> ScheduleColumns(
        timeType = ScheduleTimeType.ZONED_LOCAL_POINT,
        startsAtLocal = at,
        zoneId = zoneId,
    )

    is ScheduleWindow.AllDay -> ScheduleColumns(
        timeType = ScheduleTimeType.ALL_DAY,
        startsOnDate = startDate,
        endsOnDate = endDate,
    )
}

private data class ScheduleColumns(
    val timeType: ScheduleTimeType,
    val startsAtInstant: Instant? = null,
    val endsAtInstant: Instant? = null,
    val startsAtLocal: LocalDateTime? = null,
    val endsAtLocal: LocalDateTime? = null,
    val zoneId: String? = null,
    val startsOnDate: LocalDate? = null,
    val endsOnDate: LocalDate? = null,
)
