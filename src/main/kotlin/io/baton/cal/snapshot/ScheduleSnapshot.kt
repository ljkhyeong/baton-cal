package io.baton.cal.snapshot

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleWindow
import java.time.Instant
import java.util.UUID

data class ScheduleSnapshot(
    val eventId: UUID,
    val occurredAt: Instant,
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

        when (val schedule = snapshot.schedule) {
            is ScheduleWindow.UtcInstant -> {
                string("UTC_INSTANT")
                string(schedule.start.toString())
                string(schedule.end.toString())
            }

            is ScheduleWindow.UtcPoint -> {
                string("UTC_POINT")
                string(schedule.at.toString())
            }

            is ScheduleWindow.ZonedLocal -> {
                string("ZONED_LOCAL")
                string(schedule.start.toString())
                string(schedule.end.toString())
                string(schedule.zoneId)
            }

            is ScheduleWindow.ZonedLocalPoint -> {
                string("ZONED_LOCAL_POINT")
                string(schedule.at.toString())
                string(schedule.zoneId)
            }

            is ScheduleWindow.AllDay -> {
                string("ALL_DAY")
                string(schedule.startDate.toString())
                string(schedule.endDate.toString())
            }
        }
    }
}
