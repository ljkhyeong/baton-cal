package io.baton.cal.persistence

import io.baton.cal.calendar.CalendarItem
import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleTimeType
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.snapshot.ScheduleSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * `calendar_item` 행이다. 시간 형태마다 사용하는 열만 채우고 나머지 시간 열은 비우므로 [ScheduleWindow]와의
 * 변환을 이 파일에서만 한다. 새 시간 형태를 추가하면 저장([from])과 복원([toCalendarItem])을 함께 고치고
 * `ck_calendar_item_time_shape`를 마이그레이션에서 함께 갱신한다.
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
    val sourceUpdatedAt: Instant,
    val acceptedAt: Instant,
    val startsAtInstant: Instant? = null,
    val endsAtInstant: Instant? = null,
    val startsAtLocal: LocalDateTime? = null,
    val endsAtLocal: LocalDateTime? = null,
    val zoneId: String? = null,
    val startsOnDate: LocalDate? = null,
    val endsOnDate: LocalDate? = null,
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
    )

    companion object {
        fun from(snapshot: ScheduleSnapshot, acceptedAt: Instant): CalendarItemRow {
            val row = CalendarItemRow(
                sourceItemId = snapshot.sourceItemId,
                seasonId = snapshot.seasonId,
                revision = snapshot.revision,
                status = snapshot.status,
                summary = snapshot.summary,
                description = snapshot.description,
                location = snapshot.location,
                timeType = snapshot.schedule.type,
                sourceUpdatedAt = snapshot.sourceUpdatedAt,
                acceptedAt = acceptedAt,
            )
            return when (val schedule = snapshot.schedule) {
                is ScheduleWindow.UtcInstant -> row.copy(startsAtInstant = schedule.start, endsAtInstant = schedule.end)
                is ScheduleWindow.UtcPoint -> row.copy(startsAtInstant = schedule.at)
                is ScheduleWindow.ZonedLocal -> row.copy(
                    startsAtLocal = schedule.start,
                    endsAtLocal = schedule.end,
                    zoneId = schedule.zoneId,
                )

                is ScheduleWindow.ZonedLocalPoint -> row.copy(startsAtLocal = schedule.at, zoneId = schedule.zoneId)
                is ScheduleWindow.AllDay -> row.copy(startsOnDate = schedule.startDate, endsOnDate = schedule.endDate)
            }
        }
    }
}
