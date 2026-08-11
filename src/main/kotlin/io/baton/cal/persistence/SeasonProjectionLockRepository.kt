package io.baton.cal.persistence

import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class SeasonProjectionLockRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * Creates the lock row if needed and takes a PostgreSQL row lock.
     * The caller must invoke this inside the transaction that updates calendar
     * items and the materialized season feed.
     */
    fun acquire(seasonId: UUID) {
        jdbcClient.sql(
            """
            INSERT INTO season_projection_lock (season_id)
            VALUES (:seasonId)
            ON CONFLICT (season_id) DO NOTHING
            """.trimIndent(),
        )
            .param("seasonId", seasonId)
            .update()

        jdbcClient.sql(
            """
            SELECT season_id
            FROM season_projection_lock
            WHERE season_id = :seasonId
            FOR UPDATE
            """.trimIndent(),
        )
            .param("seasonId", seasonId)
            .query(UUID::class.java)
            .single()
    }
}
