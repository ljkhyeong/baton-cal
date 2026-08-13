package io.baton.cal.calendar

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import net.fortuna.ical4j.model.Property
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

class IcsCalendarRendererTest {
    private val renderer = IcsCalendarRenderer()
    private val seasonId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `empty feed matches canonical golden without timezone or event components`() {
        val rendered = renderer.render(seasonId, emptyList())
        val calendar = rendered.bytes.parseIcalendar()

        assertThat(rendered.bytes).isEqualTo(goldenFixture("season-empty.ics.b64"))
        assertThat(rendered.itemCount).isZero()
        assertThat(rendered.lastModified).isEqualTo(Instant.EPOCH)
        assertThat(calendar.events()).isEmpty()
        assertThat(calendar.timeZones()).isEmpty()
        assertCanonicalCrLf(rendered.bytes)
    }

    @Test
    fun `UTC snapshot is rendered deterministically with stable identity and revision`() {
        val item = CalendarItem(
            sourceItemId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            seasonId = seasonId,
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
        val event = first.bytes.parseIcalendar().events().single()

        assertThat(first.bytes).isEqualTo(goldenFixture("season-utc.ics.b64"))
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
    fun `zoned local cancellation across DST and midnight matches canonical golden`() {
        val item = CalendarItem(
            sourceItemId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            seasonId = seasonId,
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
        val event = calendar.events().single()
        val timeZone = calendar.timeZones().single()
        val start = event.requiredProperty(Property.DTSTART)
        val end = event.requiredProperty(Property.DTEND)

        assertThat(rendered.bytes)
            .isEqualTo(goldenFixture("season-zoned-midnight-cancellation.ics.b64"))
        assertThat(rendered.itemCount).isOne()
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
    fun `event order is stable and folded physical lines stay within 75 UTF-8 octets`() {
        val laterId = item(
            sourceItemId = "ffffffff-ffff-ffff-ffff-ffffffffffff",
            summary = "매우 긴 한글 일정 이름 ".repeat(12),
        )
        val earlierId = item(
            sourceItemId = "00000000-0000-0000-0000-000000000001",
            summary = "short",
        )

        val first = renderer.render(seasonId, listOf(laterId, earlierId))
        val second = renderer.render(seasonId, listOf(earlierId, laterId))
        val lines = first.bytes.toString(StandardCharsets.UTF_8).split("\r\n").dropLast(1)

        assertThat(first.bytes).isEqualTo(second.bytes)
        assertThat(lines).allSatisfy { line ->
            assertThat(line.toByteArray(StandardCharsets.UTF_8).size).isLessThanOrEqualTo(75)
        }
        assertThat(lines).anySatisfy { line -> assertThat(line).startsWith(" ") }
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
        seasonId = seasonId,
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

    private fun goldenFixture(name: String): ByteArray = Base64.getMimeDecoder().decode(
        Files.readString(Path.of("contracts/golden", name)),
    )

    private fun assertCanonicalCrLf(bytes: ByteArray) {
        val text = bytes.toString(StandardCharsets.UTF_8)

        assertThat(text).endsWith("\r\n")
        assertThat(text.replace("\r\n", "")).doesNotContain("\r", "\n")
    }
}
