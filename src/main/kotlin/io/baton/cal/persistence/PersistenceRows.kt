package io.baton.cal.persistence

import java.time.Instant
import java.util.UUID

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
