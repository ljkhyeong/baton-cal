package io.baton.cal.snapshot

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class SnapshotFingerprintTest {
    @Test
    fun `전달 봉투 필드는 원본 스냅샷 지문을 바꾸지 않는다`() {
        val original = snapshot()
        val originalHash = SnapshotFingerprint.sha256(original)
        val changedEnvelopes = listOf(
            original.copy(eventId = UUID.fromString("22222222-2222-2222-2222-222222222222")),
            original.copy(occurredAt = Instant.parse("2026-08-11T01:01:00Z")),
        )

        changedEnvelopes.forEach { changed ->
            assertThat(SnapshotFingerprint.sha256(changed)).isEqualTo(originalHash)
        }
    }

    @Test
    fun `지문이 소유한 원본 필드는 모두 지문을 바꾼다`() {
        val original = snapshot()
        val originalHash = SnapshotFingerprint.sha256(original)
        val changedSources = listOf(
            original.copy(sourceItemId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")),
            original.copy(seasonId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")),
            original.copy(revision = 4),
            original.copy(status = CalendarItemStatus.CANCELLED),
            original.copy(summary = "변경된 일정"),
            original.copy(description = "일정 설명"),
            original.copy(location = null),
            original.copy(sourceUpdatedAt = Instant.parse("2026-08-11T00:31:00Z")),
        )

        changedSources.forEach { changed ->
            assertThat(SnapshotFingerprint.sha256(changed)).isNotEqualTo(originalHash)
        }
    }

    @Test
    fun `시간 유형과 각 값은 지문을 바꾼다`() {
        val utcInterval = ScheduleWindow.UtcInstant(
            start = Instant.parse("2026-09-01T01:00:00Z"),
            end = Instant.parse("2026-09-01T02:00:00Z"),
        )
        val utcPoint = ScheduleWindow.UtcPoint(
            at = Instant.parse("2026-09-01T01:00:00Z"),
        )
        val zonedInterval = ScheduleWindow.ZonedLocal(
            start = LocalDateTime.parse("2026-09-01T10:00:00"),
            end = LocalDateTime.parse("2026-09-01T11:00:00"),
            zoneId = "America/New_York",
        )
        val zonedPoint = ScheduleWindow.ZonedLocalPoint(
            at = LocalDateTime.parse("2026-09-01T10:00:00"),
            zoneId = "America/New_York",
        )
        val allDay = ScheduleWindow.AllDay(
            startDate = LocalDate.parse("2026-09-01"),
            endDate = LocalDate.parse("2026-09-02"),
        )
        val schedulesAndVariants = listOf(
            utcInterval to listOf(
                utcInterval.copy(start = Instant.parse("2026-09-01T00:59:00Z")),
                utcInterval.copy(end = Instant.parse("2026-09-01T02:01:00Z")),
            ),
            utcPoint to listOf(
                utcPoint.copy(at = Instant.parse("2026-09-01T01:01:00Z")),
            ),
            zonedInterval to listOf(
                zonedInterval.copy(start = LocalDateTime.parse("2026-09-01T09:59:00")),
                zonedInterval.copy(end = LocalDateTime.parse("2026-09-01T11:01:00")),
                zonedInterval.copy(zoneId = "Europe/Paris"),
            ),
            zonedPoint to listOf(
                zonedPoint.copy(at = LocalDateTime.parse("2026-09-01T10:01:00")),
                zonedPoint.copy(zoneId = "Europe/Paris"),
            ),
            allDay to listOf(
                allDay.copy(startDate = LocalDate.parse("2026-08-31")),
                allDay.copy(endDate = LocalDate.parse("2026-09-03")),
            ),
        )

        schedulesAndVariants.forEach { (original, changedSchedules) ->
            val originalHash = scheduleFingerprint(original)
            changedSchedules.forEach { changed ->
                assertThat(scheduleFingerprint(changed)).isNotEqualTo(originalHash)
            }
        }
        assertThat(schedulesAndVariants.map { (schedule, _) -> scheduleFingerprint(schedule) })
            .doesNotHaveDuplicates()
    }

    private fun scheduleFingerprint(schedule: ScheduleWindow): String =
        SnapshotFingerprint.sha256(snapshot().copy(schedule = schedule))

    private fun snapshot(
        eventId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        occurredAt: Instant = Instant.parse("2026-08-11T01:00:00Z"),
    ): ScheduleSnapshot = ScheduleSnapshot(
        eventId = eventId,
        occurredAt = occurredAt,
        sourceItemId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        seasonId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        revision = 3,
        status = CalendarItemStatus.ACTIVE,
        summary = "Opening",
        description = null,
        location = "Seoul",
        schedule = ScheduleWindow.UtcInstant(
            start = Instant.parse("2026-09-01T01:00:00Z"),
            end = Instant.parse("2026-09-01T02:00:00Z"),
        ),
        sourceUpdatedAt = Instant.parse("2026-08-11T00:30:00Z"),
    )
}
