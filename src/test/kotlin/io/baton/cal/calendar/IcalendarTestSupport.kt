package io.baton.cal.calendar

import kotlin.io.encoding.Base64
import kotlin.io.path.Path
import kotlin.io.path.readText
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.parameter.TzId

internal fun ByteArray.parseIcalendar(): Calendar = inputStream().use { input ->
    CalendarBuilder().build(input)
}

internal fun goldenIcalendarFixture(name: String): ByteArray = Base64.Mime.decode(
    Path("contracts/golden", name).readText(),
)

internal fun Calendar.events(): List<VEvent> = componentList.get(Component.VEVENT)

internal fun Calendar.requiredEvent(): VEvent = componentList.getRequired(Component.VEVENT)

internal fun Calendar.timeZones(): List<VTimeZone> = componentList.get(Component.VTIMEZONE)

internal fun Calendar.requiredTimeZone(): VTimeZone = componentList.getRequired(Component.VTIMEZONE)

internal fun Component.requiredProperty(name: String): Property =
    propertyList.getRequired(name)

internal fun Component.requiredPropertyValue(name: String): String = requiredProperty(name).value

internal fun Property.requiredTimeZoneId(): String =
    getRequiredParameter<TzId>(Parameter.TZID).value
