package io.baton.cal.calendar

import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.ComponentList
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.PropertyList
import net.fortuna.ical4j.model.TimeZoneRegistryImpl
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
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class RenderedCalendar(
    val bytes: ByteArray,
    val etag: String,
    val lastModified: Instant,
    val itemCount: Int,
)

@Component
class IcsCalendarRenderer {
    private val timeZoneRegistry = TimeZoneRegistryImpl()

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
        val digest = DigestUtils.sha256Hex(bytes)

        return RenderedCalendar(
            bytes = bytes,
            etag = "\"$digest\"",
            lastModified = sortedItems.maxOfOrNull(CalendarItem::acceptedAt)
                ?.truncatedTo(ChronoUnit.SECONDS)
                ?: Instant.EPOCH,
            itemCount = sortedItems.size,
        )
    }

    private fun timeZones(items: List<CalendarItem>): List<CalendarComponent> = items
        .mapNotNull { (it.schedule as? ScheduleWindow.ZonedLocal)?.zoneId }
        .distinct()
        .sorted()
        .map { zoneId ->
            requireNotNull(timeZoneRegistry.getTimeZone(zoneId)) {
                "unsupported timezone: $zoneId"
            }.vTimeZone
        }

    private fun event(item: CalendarItem): VEvent {
        val updatedAt = item.sourceUpdatedAt.truncatedTo(ChronoUnit.SECONDS)
        val timeProperties: List<Property> = when (val schedule = item.schedule) {
            is ScheduleWindow.UtcInstant -> listOf(
                DtStart(schedule.start.truncatedTo(ChronoUnit.SECONDS)),
                DtEnd(schedule.end.truncatedTo(ChronoUnit.SECONDS)),
            )

            is ScheduleWindow.ZonedLocal -> {
                val parameters = ParameterList(listOf(TzId(schedule.zoneId)))
                listOf(
                    DtStart(parameters, schedule.start.truncatedTo(ChronoUnit.SECONDS)),
                    DtEnd(parameters, schedule.end.truncatedTo(ChronoUnit.SECONDS)),
                )
            }
        }

        return VEvent(
            PropertyList(
                listOf<Property>(
                    Uid("${item.sourceItemId}@$UID_DOMAIN"),
                    DtStamp(updatedAt),
                    LastModified(updatedAt),
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

        // iCal4j counts UTF-16 characters, so 25 keeps even three-byte BMP text
        // and the continuation space within RFC 5545's 75-octet recommendation.
        const val UTF8_SAFE_FOLD_LENGTH = 25
    }
}
