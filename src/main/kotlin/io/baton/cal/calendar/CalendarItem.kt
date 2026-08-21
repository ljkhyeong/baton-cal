package io.baton.cal.calendar

import net.fortuna.ical4j.model.TimeZone
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
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
            requirePositiveSecondRange(start.truncatedTo(ChronoUnit.SECONDS), end.truncatedTo(ChronoUnit.SECONDS))
        }
    }

    data class ZonedLocal(
        val start: LocalDateTime,
        val end: LocalDateTime,
        val zoneId: String,
    ) : ScheduleWindow {
        internal val calendarTimeZone: TimeZone

        init {
            requirePositiveSecondRange(start.truncatedTo(ChronoUnit.SECONDS), end.truncatedTo(ChronoUnit.SECONDS))
            require(zoneId in ZoneId.getAvailableZoneIds()) { "zoneId must be an IANA timezone" }
            calendarTimeZone = requireNotNull(CalendarTimeZones.findExact(zoneId)) {
                "zoneId must be preserved exactly by the calendar renderer"
            }
            val zone = ZoneId.of(zoneId)
            require(zone.rules.getValidOffsets(start).isNotEmpty()) { "start must not be in a DST gap" }
            require(zone.rules.getValidOffsets(end).isNotEmpty()) { "end must not be in a DST gap" }
        }
    }
}

private fun <T : Comparable<T>> requirePositiveSecondRange(start: T, end: T) {
    require(end > start) { "end must be after start at iCalendar second precision" }
}

private object CalendarTimeZones {
    private val registry = TimeZoneRegistryFactory.getInstance().createRegistry()

    fun findExact(zoneId: String): TimeZone? = registry
        .getTimeZone(zoneId)
        ?.takeIf { it.id == zoneId }
}

data class CalendarItem(
    val sourceItemId: UUID,
    val revision: Int,
    val status: CalendarItemStatus,
    val summary: String,
    val description: String?,
    val location: String?,
    val schedule: ScheduleWindow,
    val sourceUpdatedAt: Instant,
    val acceptedAt: Instant,
)
