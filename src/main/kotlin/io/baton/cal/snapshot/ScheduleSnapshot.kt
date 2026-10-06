package io.baton.cal.snapshot

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleWindow
import java.time.Instant
import java.util.UUID

data class ScheduleSnapshot(
    val eventId: UUID,
    val sourceItemId: UUID,
    val seasonId: UUID,
    val revision: Int,
    val status: CalendarItemStatus,
    val summary: String,
    val description: String?,
    val location: String?,
    val schedule: ScheduleWindow,
    val sourceUpdatedAt: Instant,
)

enum class SnapshotIngestionResult {
    APPLIED,
    DUPLICATE,
    STALE,
}

/** 채택한 일정 항목의 개정 번호와 상태다. 내부 상태 조회가 이 값을 그대로 응답한다. */
data class AcceptedItemStatus(
    val sourceItemId: UUID,
    val seasonId: UUID,
    val revision: Int,
    val status: CalendarItemStatus,
    val sourceUpdatedAt: Instant,
)

object SnapshotFingerprint {
    fun sha256(snapshot: ScheduleSnapshot): String = DigestWriter.sha256 {
        string("baton-cal-snapshot-v1")
        string(snapshot.sourceItemId.toString())
        string(snapshot.seasonId.toString())
        int(snapshot.revision)
        string(snapshot.status.name)
        string(snapshot.summary)
        nullableString(snapshot.description)
        nullableString(snapshot.location)
        string(snapshot.sourceUpdatedAt.toString())

        val schedule = snapshot.schedule
        string(schedule.type.name)
        when (schedule) {
            is ScheduleWindow.UtcInstant -> listOf(schedule.start, schedule.end)
            is ScheduleWindow.UtcPoint -> listOf(schedule.at)
            is ScheduleWindow.ZonedLocal -> listOf(schedule.start, schedule.end, schedule.zoneId)
            is ScheduleWindow.ZonedLocalPoint -> listOf(schedule.at, schedule.zoneId)
            is ScheduleWindow.AllDay -> listOf(schedule.startDate, schedule.endDate)
        }.forEach { string(it.toString()) }
    }
}
