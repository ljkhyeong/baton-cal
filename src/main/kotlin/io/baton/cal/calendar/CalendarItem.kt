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

/** 일정 시간 형태다. 이름은 계약 JSON의 `time.type`, 스냅샷 지문, `calendar_item.time_type` 값으로 쓰인다. */
enum class ScheduleTimeType {
    UTC_INSTANT,
    UTC_POINT,
    ZONED_LOCAL,
    ZONED_LOCAL_POINT,
    ALL_DAY,
}

sealed class ScheduleWindow(val type: ScheduleTimeType) {
    /** IANA 시간대의 현지 시각을 쓰는 형태다. */
    interface Zoned {
        val zoneId: String
    }

    data class UtcInstant(
        val start: Instant,
        val end: Instant,
    ) : ScheduleWindow(ScheduleTimeType.UTC_INSTANT) {
        init {
            requirePositiveSecondRange(start.truncatedTo(ChronoUnit.SECONDS), end.truncatedTo(ChronoUnit.SECONDS))
        }
    }

    data class UtcPoint(
        val at: Instant,
    ) : ScheduleWindow(ScheduleTimeType.UTC_POINT)

    data class ZonedLocal(
        val start: LocalDateTime,
        val end: LocalDateTime,
        override val zoneId: String,
    ) : ScheduleWindow(ScheduleTimeType.ZONED_LOCAL), Zoned {
        init {
            requirePositiveSecondRange(start.truncatedTo(ChronoUnit.SECONDS), end.truncatedTo(ChronoUnit.SECONDS))
            val zoneRules = CalendarTimeZones.zoneRules(zoneId)
            requireValidLocalTime(zoneRules, start, "start")
            requireValidLocalTime(zoneRules, end, "end")
        }
    }

    data class ZonedLocalPoint(
        val at: LocalDateTime,
        override val zoneId: String,
    ) : ScheduleWindow(ScheduleTimeType.ZONED_LOCAL_POINT), Zoned {
        init {
            requireValidLocalTime(CalendarTimeZones.zoneRules(zoneId), at, "at")
        }
    }

    data class AllDay(
        val startDate: LocalDate,
        val endDate: LocalDate,
    ) : ScheduleWindow(ScheduleTimeType.ALL_DAY) {
        init {
            require(endDate > startDate) { "endDate must be after startDate" }
        }
    }
}

private fun <T : Comparable<T>> requirePositiveSecondRange(start: T, end: T) {
    require(end > start) { "end must be after start at iCalendar second precision" }
}

private fun requireValidLocalTime(zoneRules: ZoneRules, localDateTime: LocalDateTime, fieldName: String) {
    require(zoneRules.getValidOffsets(localDateTime).isNotEmpty()) {
        "$fieldName must not be in a DST gap"
    }
}

/**
 * 입력 검증과 캘린더 출력이 같은 iCal4j 시간대 데이터를 쓴다. 레지스트리가 시간대를 보관하므로 여기서는
 * 시간대에서 만든 [ZoneRules]만 보관한다. 별칭은 다른 ID의 시간대가 되므로 거부한다.
 */
internal object CalendarTimeZones {
    private val registry = TimeZoneRegistryFactory.getInstance().createRegistry()
    private val zoneRules = ConcurrentHashMap<String, ZoneRules>()

    fun timeZone(zoneId: String): TimeZone =
        requireNotNull(registry.getTimeZone(zoneId)?.takeIf { it.id == zoneId }) {
            "zoneId must be preserved exactly by the calendar renderer"
        }

    fun zoneRules(zoneId: String): ZoneRules = zoneRules.computeIfAbsent(zoneId) {
        ZoneRulesBuilder().vTimeZone(timeZone(it).vTimeZone).build()
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
)
