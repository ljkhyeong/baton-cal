package io.baton.cal.snapshot

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SnapshotFingerprintTest {
    @Test
    fun `delivery envelope does not change the source snapshot fingerprint`() {
        val first = snapshot(
            eventId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            occurredAt = Instant.parse("2026-08-11T01:00:00Z"),
        )
        val retriedByAnotherEnvelope = snapshot(
            eventId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            occurredAt = Instant.parse("2026-08-11T01:01:00Z"),
        )

        assertThat(SnapshotFingerprint.sha256(first))
            .isEqualTo(SnapshotFingerprint.sha256(retriedByAnotherEnvelope))
    }

    @Test
    fun `source revision and content change the fingerprint`() {
        val original = snapshot()

        assertThat(SnapshotFingerprint.sha256(original.copy(revision = 4)))
            .isNotEqualTo(SnapshotFingerprint.sha256(original))
        assertThat(SnapshotFingerprint.sha256(original.copy(summary = "changed")))
            .isNotEqualTo(SnapshotFingerprint.sha256(original))
    }

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
