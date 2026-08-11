package io.baton.cal.calendar

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
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
        )

        val first = renderer.render(seasonId, listOf(item))
        val rebuilt = renderer.render(seasonId, listOf(item))
        val text = first.bytes.toString(StandardCharsets.UTF_8).unfolded()
        val golden = Base64.getMimeDecoder().decode(
            Files.readString(Path.of("contracts/golden/season-utc.ics.b64")),
        )

        assertThat(first.bytes).isEqualTo(golden)
        assertThat(first.bytes).isEqualTo(rebuilt.bytes)
        assertThat(first.etag).isEqualTo(rebuilt.etag)
        assertThat(first.lastModified).isEqualTo(Instant.parse("2026-08-11T12:34:56Z"))
        assertThat(text).contains("UID:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa@cal.baton\r\n")
        assertThat(text).contains("SEQUENCE:7\r\n")
        assertThat(text).contains("DTSTART:20260812T010000Z\r\n")
        assertThat(text).contains("SUMMARY:개막전\\, A팀\\; B팀\r\n")
        assertThat(text).contains("DESCRIPTION:첫 줄\\n둘째 줄\r\n")
        assertThat(text).contains("LOCATION:서울\\\\주경기장\r\n")
        assertThat(text).endsWith("END:VCALENDAR\r\n")
    }

    @Test
    fun `zoned local snapshot preserves wall time and emits timezone transitions`() {
        val item = CalendarItem(
            sourceItemId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            seasonId = seasonId,
            revision = 2,
            status = CalendarItemStatus.CANCELLED,
            summary = "DST boundary game",
            description = null,
            location = null,
            schedule = ScheduleWindow.ZonedLocal(
                start = LocalDateTime.parse("2026-11-01T01:30:00"),
                end = LocalDateTime.parse("2026-11-01T02:30:00"),
                zoneId = "America/New_York",
            ),
            sourceUpdatedAt = Instant.parse("2026-10-01T00:00:00Z"),
        )

        val rendered = renderer.render(seasonId, listOf(item))
        val text = rendered.bytes.toString(StandardCharsets.UTF_8).unfolded()

        assertThat(text).contains("BEGIN:VTIMEZONE\r\n")
        assertThat(text).contains("TZID:America/New_York\r\n")
        assertThat(text).contains("BEGIN:DAYLIGHT\r\n")
        assertThat(text).contains("BEGIN:STANDARD\r\n")
        assertThat(text).contains("DTSTART;TZID=America/New_York:20261101T013000\r\n")
        assertThat(text).contains("STATUS:CANCELLED\r\n")
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
    )

    private fun String.unfolded(): String = replace("\r\n ", "")
}
