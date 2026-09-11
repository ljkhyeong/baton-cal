package io.baton.cal.persistence

import kotlin.jvm.optionals.getOrNull
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
                last_modified
            ) VALUES (
                :seasonId,
                :representation,
                :etag,
                :lastModified
            )
            ON CONFLICT (season_id) DO UPDATE SET
                representation = EXCLUDED.representation,
                etag = EXCLUDED.etag,
                last_modified = EXCLUDED.last_modified
            WHERE season_feed_projection.representation IS DISTINCT FROM EXCLUDED.representation
               OR season_feed_projection.etag IS DISTINCT FROM EXCLUDED.etag
               OR season_feed_projection.last_modified IS DISTINCT FROM EXCLUDED.last_modified
            """.trimIndent(),
        )
            .param("seasonId", row.seasonId)
            .param("representation", row.representation)
            .param("etag", row.etag)
            .param("lastModified", row.lastModified.atOffset(ZoneOffset.UTC))
            .update()
    }

    fun findMetadataBySeasonId(seasonId: UUID): SeasonFeedProjectionMetadata? =
        jdbcClient.sql(
            """
            SELECT etag, last_modified, octet_length(representation) AS content_length
            FROM season_feed_projection
            WHERE season_id = :seasonId
            """.trimIndent(),
        )
            .param("seasonId", seasonId)
            .query(SeasonFeedProjectionMetadata::class.java)
            .optional()
            .getOrNull()
}
