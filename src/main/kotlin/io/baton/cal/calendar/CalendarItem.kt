package io.baton.cal.calendar

import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TimeZone
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.model.component.Observance
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.TzOffsetFrom
import net.fortuna.ical4j.model.property.TzOffsetTo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

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

    /**
     * 수신한 현지 시각이 내장 시간대 데이터에 존재하는지 확인한다. 저장된 행을 복원할 때는 호출하지 않는다.
     * 데이터 갱신으로 공백이 된 과거 입력 하나가 시즌 전체의 수신과 재구축을 막지 않게 하기 위해서다.
     */
    open fun requireExistingLocalTime() = Unit

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
        }

        override fun requireExistingLocalTime() {
            requireOutsideDstGap(zoneId, start, "start")
            requireOutsideDstGap(zoneId, end, "end")
        }
    }

    data class ZonedLocalPoint(
        val at: LocalDateTime,
        override val zoneId: String,
    ) : ScheduleWindow(ScheduleTimeType.ZONED_LOCAL_POINT), Zoned {
        override fun requireExistingLocalTime() = requireOutsideDstGap(zoneId, at, "at")
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

private fun requireOutsideDstGap(zoneId: String, localDateTime: LocalDateTime, fieldName: String) {
    require(!CalendarTimeZones.isInDstGap(zoneId, localDateTime)) {
        "$fieldName must not be in a DST gap"
    }
}

/**
 * 입력 검증과 캘린더 출력이 같은 iCal4j 시간대 데이터를 쓴다. 별칭은 다른 ID의 시간대가 되므로 거부한다.
 */
internal object CalendarTimeZones {
    private val registry = TimeZoneRegistryFactory.getInstance().createRegistry()

    fun timeZone(zoneId: String): TimeZone =
        requireNotNull(zoneId.takeIf(String::isNotBlank)?.let(registry::getTimeZone)?.takeIf { it.id == zoneId }) {
            "zoneId must be preserved exactly by the calendar renderer"
        }

    /**
     * 캘린더 앱이 받는 VTIMEZONE의 전환을 그대로 평가한다. iCal4j `ZoneRulesBuilder`는 끝난 RRULE을 미래 규칙으로
     * 남기고 호출 시점 이후의 RDATE 전환을 빠뜨리므로 쓰지 않는다. 반복 계산은 iCal4j `Recur`에 맡긴다.
     */
    fun isInDstGap(zoneId: String, time: LocalDateTime): Boolean =
        timeZone(zoneId).vTimeZone.observances.any { it.opensGapCovering(time) }

    private fun Observance.opensGapCovering(time: LocalDateTime): Boolean {
        val offsetFrom = getRequiredProperty<TzOffsetFrom>(Property.TZOFFSETFROM).offset
        val offsetTo = getRequiredProperty<TzOffsetTo>(Property.TZOFFSETTO).offset
        val gapSeconds = (offsetTo.totalSeconds - offsetFrom.totalSeconds).toLong()
        if (gapSeconds <= 0) return false

        // 전환 시각은 TZOFFSETFROM 기준 현지 시각이며 공백은 [전환 시각, 전환 시각 + 공백 길이)다.
        val onsetAfter = time.minusSeconds(gapSeconds)
        val start = getRequiredProperty<DtStart<LocalDateTime>>(Property.DTSTART).date
        val seed = start.atOffset(offsetFrom)
        val recurringOnsets = getProperties<RRule<OffsetDateTime>>(Property.RRULE).flatMap { rule ->
            rule.recur.getDates(seed, onsetAfter.atOffset(offsetFrom), time.atOffset(offsetFrom))
                .map(OffsetDateTime::toLocalDateTime)
        }
        val fixedOnsets = getProperties<RDate<LocalDateTime>>(Property.RDATE).flatMap { it.dates }
        return (recurringOnsets + fixedOnsets + start).any { it > onsetAfter && it <= time }
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
