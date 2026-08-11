package io.baton.cal.persistence

import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class CalendarSubscriptionRepository(
    private val jdbcClient: JdbcClient,
) {
    fun insert(row: CalendarSubscriptionRow): Boolean =
        jdbcClient.sql(
            """
            INSERT INTO calendar_subscription (
                id,
                season_id,
                token_hash,
                status,
                created_at,
                rotated_at,
                revoked_at
            ) VALUES (
                :id,
                :seasonId,
                :tokenHash,
                :status,
                :createdAt,
                :rotatedAt,
                :revokedAt
            )
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        )
            .param("id", row.id)
            .param("seasonId", row.seasonId)
            .param("tokenHash", row.tokenHash)
            .param("status", row.status.name)
            .param("createdAt", OffsetDateTime.ofInstant(row.createdAt, ZoneOffset.UTC))
            .param(
                "rotatedAt",
                row.rotatedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
                Types.TIMESTAMP_WITH_TIMEZONE,
            )
            .param(
                "revokedAt",
                row.revokedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
                Types.TIMESTAMP_WITH_TIMEZONE,
            )
            .update() == 1

    fun findById(id: UUID): CalendarSubscriptionRow? =
        jdbcClient.sql(
            """
            SELECT $COLUMNS
            FROM calendar_subscription
            WHERE id = :id
            """.trimIndent(),
        )
            .param("id", id)
            .query(::mapRow)
            .optional()
            .orElse(null)

    fun findActiveByTokenHash(tokenHash: String): CalendarSubscriptionRow? =
        jdbcClient.sql(
            """
            SELECT $COLUMNS
            FROM calendar_subscription
            WHERE token_hash = :tokenHash
              AND status = 'ACTIVE'
            """.trimIndent(),
        )
            .param("tokenHash", tokenHash)
            .query(::mapRow)
            .optional()
            .orElse(null)

    /** Rotates only the still-active credential the caller observed. */
    fun rotate(
        id: UUID,
        expectedTokenHash: String,
        replacementTokenHash: String,
        rotatedAt: Instant,
    ): Boolean =
        jdbcClient.sql(
            """
            UPDATE calendar_subscription
            SET token_hash = :replacementTokenHash,
                rotated_at = :rotatedAt
            WHERE id = :id
              AND status = 'ACTIVE'
              AND token_hash = :expectedTokenHash
            """.trimIndent(),
        )
            .param("id", id)
            .param("expectedTokenHash", expectedTokenHash)
            .param("replacementTokenHash", replacementTokenHash)
            .param("rotatedAt", OffsetDateTime.ofInstant(rotatedAt, ZoneOffset.UTC))
            .update() == 1

    fun revoke(
        id: UUID,
        expectedTokenHash: String,
        revokedAt: Instant,
    ): Boolean =
        jdbcClient.sql(
            """
            UPDATE calendar_subscription
            SET status = 'REVOKED',
                revoked_at = :revokedAt
            WHERE id = :id
              AND status = 'ACTIVE'
              AND token_hash = :expectedTokenHash
            """.trimIndent(),
        )
            .param("id", id)
            .param("expectedTokenHash", expectedTokenHash)
            .param("revokedAt", OffsetDateTime.ofInstant(revokedAt, ZoneOffset.UTC))
            .update() == 1

    private fun mapRow(resultSet: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int) =
        CalendarSubscriptionRow(
            id = resultSet.getObject("id", UUID::class.java),
            seasonId = resultSet.getObject("season_id", UUID::class.java),
            tokenHash = resultSet.getString("token_hash"),
            status = CalendarSubscriptionStatus.valueOf(resultSet.getString("status")),
            createdAt = resultSet.requiredInstant("created_at"),
            rotatedAt = resultSet.nullableInstant("rotated_at"),
            revokedAt = resultSet.nullableInstant("revoked_at"),
        )

    private companion object {
        const val COLUMNS = """
            id,
            season_id,
            token_hash,
            status,
            created_at,
            rotated_at,
            revoked_at
        """
    }
}
