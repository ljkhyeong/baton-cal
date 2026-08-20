package io.baton.cal.calendar

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

internal fun Calendar.events(): List<VEvent> = componentList.all.filterIsInstance<VEvent>()

internal fun Calendar.timeZones(): List<VTimeZone> = componentList.all.filterIsInstance<VTimeZone>()

internal fun Component.requiredProperty(name: String): Property =
    propertyList.all.first { it.name == name }

internal fun Component.requiredPropertyValue(name: String): String = requiredProperty(name).value

internal fun Property.requiredTimeZoneId(): String =
    getParameter<TzId>(Parameter.TZID).orElseThrow().value
