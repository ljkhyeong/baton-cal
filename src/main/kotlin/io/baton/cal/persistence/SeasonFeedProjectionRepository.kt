package io.baton.cal.persistence

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class SeasonFeedProjectionRepository(
    private val jdbcClient: JdbcClient,
) {
    fun upsert(row: SeasonFeedProjectionRow) {
        jdbcClient.sql(
            """
            INSERT INTO season_feed_projection (
                season_id,
                representation,
                etag,
                last_modified,
                item_count
            ) VALUES (
                :seasonId,
                :representation,
                :etag,
                :lastModified,
                :itemCount
            )
            ON CONFLICT (season_id) DO UPDATE SET
                representation = EXCLUDED.representation,
                etag = EXCLUDED.etag,
                last_modified = EXCLUDED.last_modified,
                item_count = EXCLUDED.item_count
            """.trimIndent(),
        )
            .param("seasonId", row.seasonId)
            .param("representation", row.representation)
            .param("etag", row.etag)
            .param("lastModified", OffsetDateTime.ofInstant(row.lastModified, ZoneOffset.UTC))
            .param("itemCount", row.itemCount)
            .update()
    }

    fun findBySeasonId(seasonId: UUID): SeasonFeedProjectionRow? =
        jdbcClient.sql(
            """
            SELECT
                season_id,
                representation,
                etag,
                last_modified,
                item_count
            FROM season_feed_projection
            WHERE season_id = :seasonId
            """.trimIndent(),
        )
            .param("seasonId", seasonId)
            .query { resultSet, _ -> resultSet.seasonFeedProjectionRow() }
            .optional()
            .orElse(null)
}
