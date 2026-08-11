package io.baton.cal.persistence

import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class SeasonFeedProjectionRepository(
    private val jdbcClient: JdbcClient,
) {
    fun upsert(row: SeasonFeedProjectionRow): SeasonFeedProjectionRow =
        jdbcClient.sql(
            """
            INSERT INTO season_feed_projection (
                season_id,
                representation,
                etag,
                last_modified,
                item_count,
                rebuilt_at
            ) VALUES (
                :seasonId,
                :representation,
                :etag,
                :lastModified,
                :itemCount,
                :rebuiltAt
            )
            ON CONFLICT (season_id) DO UPDATE SET
                representation = EXCLUDED.representation,
                etag = EXCLUDED.etag,
                last_modified = EXCLUDED.last_modified,
                item_count = EXCLUDED.item_count,
                rebuilt_at = EXCLUDED.rebuilt_at
            RETURNING
                season_id,
                representation,
                etag,
                last_modified,
                item_count,
                rebuilt_at
            """.trimIndent(),
        )
            .param("seasonId", row.seasonId)
            .param("representation", row.representation)
            .param("etag", row.etag)
            .param("lastModified", OffsetDateTime.ofInstant(row.lastModified, ZoneOffset.UTC))
            .param("itemCount", row.itemCount)
            .param("rebuiltAt", OffsetDateTime.ofInstant(row.rebuiltAt, ZoneOffset.UTC))
            .query(::mapRow)
            .single()

    fun findBySeasonId(seasonId: UUID): SeasonFeedProjectionRow? =
        jdbcClient.sql(
            """
            SELECT
                season_id,
                representation,
                etag,
                last_modified,
                item_count,
                rebuilt_at
            FROM season_feed_projection
            WHERE season_id = :seasonId
            """.trimIndent(),
        )
            .param("seasonId", seasonId)
            .query(::mapRow)
            .optional()
            .orElse(null)

    private fun mapRow(resultSet: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int) =
        SeasonFeedProjectionRow(
            seasonId = resultSet.getObject("season_id", UUID::class.java),
            representation = resultSet.getBytes("representation"),
            etag = resultSet.getString("etag"),
            lastModified = resultSet.requiredInstant("last_modified"),
            itemCount = resultSet.getInt("item_count"),
            rebuiltAt = resultSet.requiredInstant("rebuilt_at"),
        )
}
