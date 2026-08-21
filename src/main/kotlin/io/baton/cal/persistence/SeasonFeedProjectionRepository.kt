package io.baton.cal.persistence

import kotlin.jvm.optionals.getOrNull
import java.time.Instant
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
            """.trimIndent(),
        )
            .param("seasonId", row.seasonId)
            .param("representation", row.representation)
            .param("etag", row.etag)
            .param("lastModified", OffsetDateTime.ofInstant(row.lastModified, ZoneOffset.UTC))
            .update()
    }

    fun findLastModifiedBySeasonId(seasonId: UUID): Instant? =
        jdbcClient.sql(
            """
            SELECT last_modified
            FROM season_feed_projection
            WHERE season_id = :seasonId
            """.trimIndent(),
        )
            .param("seasonId", seasonId)
            .query(Instant::class.java)
            .optional()
            .getOrNull()
}
