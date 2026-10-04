package io.baton.cal.persistence

import io.baton.cal.calendar.CalendarItemStatus
import java.time.Instant
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

data class CalendarItemStatusRow(
    val sourceItemId: UUID,
    val seasonId: UUID,
    val revision: Int,
    val status: CalendarItemStatus,
    val sourceUpdatedAt: Instant,
)

enum class CalendarItemApplyOutcome {
    APPLIED,
    STALE,
    SCOPE_CONFLICT,
    REVISION_CONFLICT,
}

data class SeasonFeedProjectionRow(
    val seasonId: UUID,
    val representation: ByteArray,
    val etag: String,
    val lastModified: Instant,
)

data class SeasonFeedHeaders(
    val etag: String,
    val lastModified: Instant,
    val contentLength: Int,
)

enum class CalendarSubscriptionStatus {
    ACTIVE,
    REVOKED,
}

data class CalendarSubscriptionRow(
    val id: UUID,
    val seasonId: UUID,
    val tokenHash: String,
    val credentialGeneration: UUID,
    val status: CalendarSubscriptionStatus,
)
