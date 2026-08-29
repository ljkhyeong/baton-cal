package io.baton.cal.calendar

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.parameter.Value
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class IcsCalendarRendererTest {
    private val renderer = IcsCalendarRenderer()
    private val seasonId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `empty feed matches canonical golden without timezone or event components`() {
        val rendered = renderer.render(seasonId, emptyList())
        val calendar = rendered.bytes.parseIcalendar()

        assertThat(rendered.bytes).isEqualTo(goldenIcalendarFixture("season-empty.ics.b64"))
        assertThat(rendered.lastModified).isEqualTo(Instant.EPOCH)
        assertThat(calendar.events()).isEmpty()
        assertThat(calendar.timeZones()).isEmpty()
        assertCanonicalCrLf(rendered.bytes)
    }

    @Test
    fun `UTC snapshot is rendered deterministically with stable identity and revision`() {
        val item = CalendarItem(
            sourceItemId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            revision = 7,
            status = CalendarItemStatus.ACTIVE,
            summary = "개막전, A팀; B팀",
            description = "첫 줄\n둘째 줄",
            location = "서울\\주경기장",
            schedule = ScheduleWindow.UtcInstant(
                start = Instant.parse("2026-08-12T01:00:00Z"),
                end = Instant.parse("2026-08-12T02:30:00Z"),
            ),
            sourceUpdatedAt = Instant.parse("2026-08-11T12:34:56.987Z"),
            acceptedAt = Instant.parse("2026-08-11T12:34:56.987Z"),
        )

        val first = renderer.render(seasonId, listOf(item))
        val rebuilt = renderer.render(seasonId, listOf(item))
        val event = first.bytes.parseIcalendar().requiredEvent()

        assertThat(first.bytes).isEqualTo(goldenIcalendarFixture("season-utc.ics.b64"))
        assertThat(first.bytes).isEqualTo(rebuilt.bytes)
        assertThat(first.etag).isEqualTo(rebuilt.etag)
        assertThat(first.lastModified).isEqualTo(Instant.parse("2026-08-11T12:34:56Z"))
        assertThat(event.requiredPropertyValue(Property.UID))
            .isEqualTo("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa@cal.baton")
        assertThat(event.requiredPropertyValue(Property.SEQUENCE)).isEqualTo("7")
        assertThat(event.requiredPropertyValue(Property.DTSTART)).isEqualTo("20260812T010000Z")
        assertThat(event.requiredPropertyValue(Property.SUMMARY)).isEqualTo("개막전, A팀; B팀")
        assertThat(event.requiredPropertyValue(Property.DESCRIPTION)).isEqualTo("첫 줄\n둘째 줄")
        assertThat(event.requiredPropertyValue(Property.LOCATION)).isEqualTo("서울\\주경기장")
    }

    @Test
    fun `유니코드 이스케이프와 줄 접기 경계가 골든 바이트와 원문을 보존한다`() {
        val summary = "1234567890123456,접힘 경계"
        val description = "12345678901😀 뒤따르는 설명"
        val location = "실제 줄바꿈\n리터럴 \\n"
        val item = CalendarItem(
            sourceItemId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
            revision = 11,
            status = CalendarItemStatus.ACTIVE,
            summary = summary,
            description = description,
            location = location,
            schedule = ScheduleWindow.UtcInstant(
                start = Instant.parse("2026-08-14T01:02:03Z"),
                end = Instant.parse("2026-08-14T02:03:04Z"),
            ),
            sourceUpdatedAt = Instant.parse("2026-08-13T12:34:56.987Z"),
            acceptedAt = Instant.parse("2026-08-13T12:34:56.987Z"),
        )

        val rendered = renderer.render(seasonId, listOf(item))
        val event = rendered.bytes.parseIcalendar().requiredEvent()
        val physicalLines = rendered.bytes.decodeToString()
            .split("\r\n")
            .dropLast(1)

        assertThat(rendered.bytes)
            .isEqualTo(goldenIcalendarFixture("season-unicode-fold-boundaries.ics.b64"))
        assertThat(event.requiredPropertyValue(Property.SUMMARY)).isEqualTo(summary)
        assertThat(event.requiredPropertyValue(Property.DESCRIPTION)).isEqualTo(description)
        assertThat(event.requiredPropertyValue(Property.LOCATION)).isEqualTo(location)
        assertThat(physicalLines).allSatisfy { line ->
            assertThat(line.encodeToByteArray().size).isLessThanOrEqualTo(75)
        }
        assertCanonicalCrLf(rendered.bytes)
    }

    @Test
    fun `zoned local cancellation across DST and midnight matches canonical golden`() {
        val item = CalendarItem(
            sourceItemId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            revision = 9,
            status = CalendarItemStatus.CANCELLED,
            summary = "DST midnight cancellation",
            description = null,
            location = null,
            schedule = ScheduleWindow.ZonedLocal(
                start = LocalDateTime.parse("2026-10-31T23:30:00"),
                end = LocalDateTime.parse("2026-11-01T02:30:00"),
                zoneId = "America/New_York",
            ),
            sourceUpdatedAt = Instant.parse("2026-10-31T12:34:56.987Z"),
            acceptedAt = Instant.parse("2026-10-31T12:34:56.987Z"),
        )

        val rendered = renderer.render(seasonId, listOf(item))
        val calendar = rendered.bytes.parseIcalendar()
        val event = calendar.requiredEvent()
        val timeZone = calendar.requiredTimeZone()
        val start = event.requiredProperty(Property.DTSTART)
        val end = event.requiredProperty(Property.DTEND)

        assertThat(rendered.bytes)
            .isEqualTo(goldenIcalendarFixture("season-zoned-midnight-cancellation.ics.b64"))
        assertThat(rendered.lastModified).isEqualTo(Instant.parse("2026-10-31T12:34:56Z"))
        assertThat(timeZone.timeZoneId.value).isEqualTo("America/New_York")
        assertThat(timeZone.observances.map { it.name }).contains("DAYLIGHT", "STANDARD")
        assertThat(event.requiredPropertyValue(Property.UID))
            .isEqualTo("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb@cal.baton")
        assertThat(event.requiredPropertyValue(Property.SEQUENCE)).isEqualTo("9")
        assertThat(event.requiredPropertyValue(Property.STATUS)).isEqualTo("CANCELLED")
        assertThat(start.requiredTimeZoneId()).isEqualTo("America/New_York")
        assertThat(end.requiredTimeZoneId()).isEqualTo("America/New_York")
        assertThat(start.value).isEqualTo("20261031T233000")
        assertThat(end.value).isEqualTo("20261101T023000")
        assertCanonicalCrLf(rendered.bytes)
    }

    @Test
    fun `시점 일정과 종일 일정은 원본 시간 의미를 보존한다`() {
        val utcPoint = CalendarItem(
            sourceItemId = UUID.fromString("10000000-0000-0000-0000-000000000001"),
            revision = 1,
            status = CalendarItemStatus.ACTIVE,
            summary = "UTC 시점",
            description = null,
            location = null,
            schedule = ScheduleWindow.UtcPoint(Instant.parse("2026-09-01T01:02:03Z")),
            sourceUpdatedAt = Instant.parse("2026-08-20T01:00:00Z"),
            acceptedAt = Instant.parse("2026-08-20T01:00:00Z"),
        )
        val zonedPoint = CalendarItem(
            sourceItemId = UUID.fromString("20000000-0000-0000-0000-000000000002"),
            revision = 2,
            status = CalendarItemStatus.ACTIVE,
            summary = "서울 시점",
            description = null,
            location = "서울",
            schedule = ScheduleWindow.ZonedLocalPoint(
                at = LocalDateTime.parse("2026-09-02T18:30:00"),
                zoneId = "Asia/Seoul",
            ),
            sourceUpdatedAt = Instant.parse("2026-08-20T02:00:00Z"),
            acceptedAt = Instant.parse("2026-08-20T02:00:00Z"),
        )
        val allDay = CalendarItem(
            sourceItemId = UUID.fromString("30000000-0000-0000-0000-000000000003"),
            revision = 3,
            status = CalendarItemStatus.ACTIVE,
            summary = "종일 일정",
            description = null,
            location = null,
            schedule = ScheduleWindow.AllDay(
                startDate = LocalDate.parse("2026-09-03"),
                endDate = LocalDate.parse("2026-09-05"),
            ),
            sourceUpdatedAt = Instant.parse("2026-08-20T03:00:00Z"),
            acceptedAt = Instant.parse("2026-08-20T03:00:00Z"),
        )

        val rendered = renderer.render(seasonId, listOf(allDay, utcPoint))
        val zonedRendered = renderer.render(seasonId, listOf(zonedPoint))
        val calendar = rendered.bytes.parseIcalendar()
        val zonedCalendar = zonedRendered.bytes.parseIcalendar()
        val eventsByUid = calendar.events().associateBy { it.requiredPropertyValue(Property.UID) }
        val utcEvent = eventsByUid.getValue("${utcPoint.sourceItemId}@cal.baton")
        val zonedEvent = zonedCalendar.requiredEvent()
        val allDayEvent = eventsByUid.getValue("${allDay.sourceItemId}@cal.baton")

        assertThat(rendered.bytes).isEqualTo(goldenIcalendarFixture("season-point-and-all-day.ics.b64"))
        assertThat(calendar.events()).hasSize(2)
        assertThat(calendar.timeZones()).isEmpty()
        assertThat(zonedCalendar.timeZones()).hasSize(1)
        assertThat(utcEvent.requiredPropertyValue(Property.DTSTART)).isEqualTo("20260901T010203Z")
        assertThat(utcEvent.propertyList.get<Property>(Property.DTEND)).isEmpty()
        assertThat(zonedEvent.requiredPropertyValue(Property.DTSTART)).isEqualTo("20260902T183000")
        assertThat(zonedEvent.requiredProperty(Property.DTSTART).requiredTimeZoneId()).isEqualTo("Asia/Seoul")
        assertThat(zonedEvent.propertyList.get<Property>(Property.DTEND)).isEmpty()
        assertThat(allDayEvent.requiredPropertyValue(Property.DTSTART)).isEqualTo("20260903")
        assertThat(allDayEvent.requiredPropertyValue(Property.DTEND)).isEqualTo("20260905")
        assertThat(
            allDayEvent.requiredProperty(Property.DTSTART)
                .getRequiredParameter<Value>(Parameter.VALUE)
                .value,
        ).isEqualTo("DATE")
        assertThat(rendered.lastModified).isEqualTo(Instant.parse("2026-08-20T03:00:00Z"))
        assertCanonicalCrLf(rendered.bytes)
    }

    @Test
    fun `입력 순서와 무관하게 이벤트 바이트 순서가 안정적이다`() {
        val laterId = item(
            sourceItemId = "ffffffff-ffff-ffff-ffff-ffffffffffff",
            summary = "나중 일정",
        )
        val earlierId = item(
            sourceItemId = "00000000-0000-0000-0000-000000000001",
            summary = "먼저 일정",
        )

        val first = renderer.render(seasonId, listOf(laterId, earlierId))
        val second = renderer.render(seasonId, listOf(earlierId, laterId))

        assertThat(first.bytes).isEqualTo(second.bytes)
    }

    @Test
    fun `zoned local schedule rejects a wall time inside a DST gap`() {
        assertThatThrownBy {
            ScheduleWindow.ZonedLocal(
                start = LocalDateTime.parse("2026-03-08T02:30:00"),
                end = LocalDateTime.parse("2026-03-08T03:30:00"),
                zoneId = "America/New_York",
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("DST gap")
    }

    @Test
    fun `만료된 과거 DST 규칙은 현재 현지 시각을 거부하지 않는다`() {
        ScheduleWindow.ZonedLocalPoint(
            at = LocalDateTime.parse("2026-05-03T00:30:00"),
            zoneId = "Asia/Tokyo",
        )
    }

    @Test
    fun `schedule range must remain positive at iCalendar second precision`() {
        assertThatThrownBy {
            ScheduleWindow.UtcInstant(
                start = Instant.parse("2026-01-01T00:00:00.100Z"),
                end = Instant.parse("2026-01-01T00:00:00.900Z"),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("second precision")
    }

    private fun item(
        sourceItemId: String,
        summary: String,
    ): CalendarItem = CalendarItem(
        sourceItemId = UUID.fromString(sourceItemId),
        revision = 0,
        status = CalendarItemStatus.ACTIVE,
        summary = summary,
        description = null,
        location = null,
        schedule = ScheduleWindow.UtcInstant(
            start = Instant.parse("2026-01-01T00:00:00Z"),
            end = Instant.parse("2026-01-01T01:00:00Z"),
        ),
        sourceUpdatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        acceptedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun assertCanonicalCrLf(bytes: ByteArray) {
        val text = bytes.decodeToString()

        assertThat(text).endsWith("\r\n")
        assertThat(text.replace("\r\n", "")).doesNotContain("\r", "\n")
    }
}
