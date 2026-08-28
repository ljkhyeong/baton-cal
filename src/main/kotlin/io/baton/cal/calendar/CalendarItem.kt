package io.baton.cal.calendar

import net.fortuna.ical4j.model.TimeZone
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.model.ZoneRulesBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.zone.ZoneRules
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class CalendarItemStatus {
    ACTIVE,
    CANCELLED,
}

enum class ScheduleTimeType {
    UTC_INSTANT,
    UTC_POINT,
    ZONED_LOCAL,
    ZONED_LOCAL_POINT,
    ALL_DAY,
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

    data class UtcPoint(
        val at: Instant,
    ) : ScheduleWindow

    data class ZonedLocal(
        val start: LocalDateTime,
        val end: LocalDateTime,
        val zoneId: String,
    ) : ScheduleWindow {
        internal val calendarTimeZone: TimeZone

        init {
            requirePositiveSecondRange(start.truncatedTo(ChronoUnit.SECONDS), end.truncatedTo(ChronoUnit.SECONDS))
            val zone = requireCalendarZone(zoneId)
            calendarTimeZone = zone.calendarTimeZone
            requireValidLocalTime(zone.zoneRules, start, "start")
            requireValidLocalTime(zone.zoneRules, end, "end")
        }
    }

    data class ZonedLocalPoint(
        val at: LocalDateTime,
        val zoneId: String,
    ) : ScheduleWindow {
        internal val calendarTimeZone: TimeZone

        init {
            val zone = requireCalendarZone(zoneId)
            calendarTimeZone = zone.calendarTimeZone
            requireValidLocalTime(zone.zoneRules, at, "at")
        }
    }

    data class AllDay(
        val startDate: LocalDate,
        val endDate: LocalDate,
    ) : ScheduleWindow {
        init {
            require(endDate > startDate) { "endDate must be after startDate" }
        }
    }
}

private fun <T : Comparable<T>> requirePositiveSecondRange(start: T, end: T) {
    require(end > start) { "end must be after start at iCalendar second precision" }
}

private fun requireCalendarZone(zoneId: String): CalendarZone =
    requireNotNull(CalendarTimeZones.findExact(zoneId)) {
        "zoneId must be preserved exactly by the calendar renderer"
    }

private fun requireValidLocalTime(zoneRules: ZoneRules, localDateTime: LocalDateTime, fieldName: String) {
    require(zoneRules.getValidOffsets(localDateTime).isNotEmpty()) {
        "$fieldName must not be in a DST gap"
    }
}

private data class CalendarZone(
    val calendarTimeZone: TimeZone,
    val zoneRules: ZoneRules,
)

private object CalendarTimeZones {
    private val registry = TimeZoneRegistryFactory.getInstance().createRegistry()
    private val zones = ConcurrentHashMap<String, CalendarZone>()

    fun findExact(zoneId: String): CalendarZone? {
        zones[zoneId]?.let { return it }
        val timeZone = registry.getTimeZone(zoneId)?.takeIf { it.id == zoneId } ?: return null
        val zone = CalendarZone(
            calendarTimeZone = timeZone,
            zoneRules = ZoneRulesBuilder().vTimeZone(timeZone.vTimeZone).build(),
        )
        return zones.getOrPut(zoneId) { zone }
    }
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
