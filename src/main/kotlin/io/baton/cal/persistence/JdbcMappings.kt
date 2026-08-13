package io.baton.cal.persistence

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

internal fun ResultSet.requiredInstant(column: String): Instant =
    getObject(column, OffsetDateTime::class.java).toInstant()

internal fun ResultSet.nullableInstant(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

internal fun ResultSet.seasonFeedProjectionRow(): SeasonFeedProjectionRow =
    SeasonFeedProjectionRow(
        seasonId = getObject("season_id", UUID::class.java),
        representation = getBytes("representation"),
        etag = getString("etag"),
        lastModified = requiredInstant("last_modified"),
        itemCount = getInt("item_count"),
    )
