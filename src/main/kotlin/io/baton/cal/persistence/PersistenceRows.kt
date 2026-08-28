package io.baton.cal.persistence

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleTimeType
import java.time.Instant
import java.time.LocalDate
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
    val startsOnDate: LocalDate?,
    val endsOnDate: LocalDate?,
    val sourceUpdatedAt: Instant,
    val acceptedAt: Instant,
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

data class SeasonFeedProjectionMetadata(
    val etag: String,
    val lastModified: Instant,
)

data class ActiveSeasonFeedProjectionMetadata(
    val seasonId: UUID,
    val etag: String,
    val lastModified: Instant,
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
