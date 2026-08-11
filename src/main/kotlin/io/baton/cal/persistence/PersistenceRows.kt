package io.baton.cal.persistence

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleTimeType
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class SourceEventInboxRow(
    val eventId: UUID,
    val payloadHash: String,
    val sourceItemId: UUID,
    val seasonId: UUID,
    val sourceRevision: Int,
    val occurredAt: Instant,
    val receivedAt: Instant,
)

data class CalendarItemRow(
    val sourceItemId: UUID,
    val seasonId: UUID,
    val revision: Int,
    val payloadHash: String,
    val status: CalendarItemStatus,
    val summary: String,
    val description: String?,
    val location: String?,
    val timeType: ScheduleTimeType,
    val startsAtInstant: Instant?,
    val endsAtInstant: Instant?,
    val startsAtLocal: LocalDateTime?,
    val endsAtLocal: LocalDateTime?,
    val zoneId: String?,
    val sourceUpdatedAt: Instant,
    val acceptedAt: Instant,
)

enum class CalendarItemApplyOutcome {
    APPLIED,
    DUPLICATE,
    STALE,
    CONFLICT,
}

data class CalendarItemApplyResult(
    val outcome: CalendarItemApplyOutcome,
    val current: CalendarItemRow,
)

data class SeasonFeedProjectionRow(
    val seasonId: UUID,
    val representation: ByteArray,
    val etag: String,
    val lastModified: Instant,
    val itemCount: Int,
    val rebuiltAt: Instant,
)

enum class CalendarSubscriptionStatus {
    ACTIVE,
    REVOKED,
}

data class CalendarSubscriptionRow(
    val id: UUID,
    val seasonId: UUID,
    val tokenHash: String,
    val status: CalendarSubscriptionStatus,
    val createdAt: Instant,
    val rotatedAt: Instant?,
    val revokedAt: Instant?,
)
