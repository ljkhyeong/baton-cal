package io.baton.cal.calendar

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class CalendarItemStatus {
    ACTIVE,
    CANCELLED,
}

enum class ScheduleTimeType {
    UTC_INSTANT,
    ZONED_LOCAL,
}

sealed interface ScheduleWindow {
    data class UtcInstant(
        val start: Instant,
        val end: Instant,
    ) : ScheduleWindow {
        init {
            require(end.truncatedTo(ChronoUnit.SECONDS).isAfter(start.truncatedTo(ChronoUnit.SECONDS))) {
                "end must be after start at iCalendar second precision"
            }
        }
    }

    data class ZonedLocal(
        val start: LocalDateTime,
        val end: LocalDateTime,
        val zoneId: String,
    ) : ScheduleWindow {
        init {
            require(end.truncatedTo(ChronoUnit.SECONDS).isAfter(start.truncatedTo(ChronoUnit.SECONDS))) {
                "end must be after start at iCalendar second precision"
            }
            require(zoneId in ZoneId.getAvailableZoneIds()) { "zoneId must be an IANA timezone" }
            val zone = ZoneId.of(zoneId)
            require(zone.rules.getValidOffsets(start).isNotEmpty()) { "start must not be in a DST gap" }
            require(zone.rules.getValidOffsets(end).isNotEmpty()) { "end must not be in a DST gap" }
        }
    }
}

data class CalendarItem(
    val sourceItemId: UUID,
    val seasonId: UUID,
    val revision: Int,
    val status: CalendarItemStatus,
    val summary: String,
    val description: String?,
    val location: String?,
    val schedule: ScheduleWindow,
    val sourceUpdatedAt: Instant,
    val acceptedAt: Instant = sourceUpdatedAt,
)
