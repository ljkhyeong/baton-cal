package io.baton.cal.calendar

import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.ComponentList
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.PropertyList
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.LastModified
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.Sequence
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.XProperty
import net.fortuna.ical4j.model.property.immutable.ImmutableCalScale
import net.fortuna.ical4j.model.property.immutable.ImmutableStatus
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class RenderedCalendar(
    val bytes: ByteArray,
    val etag: String,
    val lastModified: Instant,
)

@Component
class IcsCalendarRenderer {
    fun render(
        seasonId: UUID,
        items: List<CalendarItem>,
    ): RenderedCalendar {
        val sortedItems = items.sortedBy { it.sourceItemId.toString() }
        val calendar = Calendar(
            PropertyList(
                listOf(
                    ProdId(PRODUCT_ID),
                    ImmutableVersion.VERSION_2_0,
                    ImmutableCalScale.GREGORIAN,
                    XProperty("X-WR-CALNAME", "BATON season $seasonId"),
                ),
            ),
            ComponentList(timeZones(sortedItems) + sortedItems.map(::event)),
        )
        val bytes = ByteArrayOutputStream().also { output ->
            CalendarOutputter(false, UTF8_SAFE_FOLD_LENGTH).output(calendar, output)
        }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

        return RenderedCalendar(
            bytes = bytes,
            etag = "\"$digest\"",
            lastModified = sortedItems.maxOfOrNull(CalendarItem::acceptedAt)
                ?.truncatedTo(ChronoUnit.SECONDS)
                ?: Instant.EPOCH,
        )
    }

    private fun timeZones(items: List<CalendarItem>): List<CalendarComponent> = items
        .mapNotNull { it.schedule as? ScheduleWindow.ZonedLocal }
        .distinctBy(ScheduleWindow.ZonedLocal::zoneId)
        .sortedBy(ScheduleWindow.ZonedLocal::zoneId)
        .map { it.calendarTimeZone.vTimeZone }

    private fun event(item: CalendarItem): VEvent {
        val timeProperties: List<Property> = when (val schedule = item.schedule) {
            is ScheduleWindow.UtcInstant -> listOf(
                DtStart(schedule.start),
                DtEnd(schedule.end),
            )

            is ScheduleWindow.ZonedLocal -> {
                val parameters = ParameterList(listOf(TzId(schedule.zoneId)))
                listOf(
                    DtStart(parameters, schedule.start),
                    DtEnd(parameters, schedule.end),
                )
            }
        }

        return VEvent(
            PropertyList(
                listOf<Property>(
                    Uid("${item.sourceItemId}@$UID_DOMAIN"),
                    DtStamp(item.sourceUpdatedAt),
                    LastModified(item.sourceUpdatedAt),
                    Sequence(item.revision),
                    if (item.status == CalendarItemStatus.CANCELLED) {
                        ImmutableStatus.VEVENT_CANCELLED
                    } else {
                        ImmutableStatus.VEVENT_CONFIRMED
                    },
                ) + timeProperties + listOfNotNull(
                    Summary(item.summary),
                    item.description?.let(::Description),
                    item.location?.let(::Location),
                ),
            ),
        )
    }

    private companion object {
        const val PRODUCT_ID = "-//BATON//BATON CAL//EN"
        const val UID_DOMAIN = "cal.baton"

        // iCal4j는 UTF-16 문자 수를 세므로 25로 설정해야 3바이트 BMP 문자와
        // 연속 줄의 공백을 포함해 RFC 5545의 75 옥텟 권고를 지킬 수 있다.
        const val UTF8_SAFE_FOLD_LENGTH = 25
    }
}
