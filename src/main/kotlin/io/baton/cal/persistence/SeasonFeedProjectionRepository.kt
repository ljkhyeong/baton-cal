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
    /** 시즌 표현을 저장한다. ETag는 표현 바이트의 해시이므로 ETag와 Last-Modified가 같으면 행을 다시 쓰지 않는다. */
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
            WHERE (season_feed_projection.etag, season_feed_projection.last_modified)
                IS DISTINCT FROM (EXCLUDED.etag, EXCLUDED.last_modified)
            """.trimIndent(),
        )
            .param("seasonId", row.seasonId)
            .param("representation", row.representation)
            .param("etag", row.etag)
            .param("lastModified", row.lastModified.atOffset(ZoneOffset.UTC))
            .update()
    }

    fun findHeadersBySeasonId(seasonId: UUID): SeasonFeedHeaders? =
        jdbcClient.sql(
            """
            SELECT etag, last_modified, octet_length(representation) AS content_length
            FROM season_feed_projection
            WHERE season_id = :seasonId
            """.trimIndent(),
        )
            .param("seasonId", seasonId)
            .query(SeasonFeedHeaders::class.java)
            .optional()
            .getOrNull()
}
