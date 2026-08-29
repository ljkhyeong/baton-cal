package io.baton.cal.persistence

import kotlin.jvm.optionals.getOrNull
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class CalendarSubscriptionRepository(
    private val jdbcClient: JdbcClient,
) {
    fun insert(row: CalendarSubscriptionRow) {
        jdbcClient.sql(
            """
            INSERT INTO calendar_subscription (
                id,
                season_id,
                token_hash,
                credential_generation,
                status
            ) VALUES (
                :id,
                :seasonId,
                :tokenHash,
                :credentialGeneration,
                :status
            )
            """.trimIndent(),
        )
            .param("id", row.id)
            .param("seasonId", row.seasonId)
            .param("tokenHash", row.tokenHash)
            .param("credentialGeneration", row.credentialGeneration)
            .param("status", row.status.name)
            .update()
    }

    fun findById(id: UUID): CalendarSubscriptionRow? =
        jdbcClient.sql(
            """
            SELECT $COLUMNS
            FROM calendar_subscription
            WHERE id = :id
            """.trimIndent(),
        )
            .param("id", id)
            .query(CalendarSubscriptionRow::class.java)
            .optional()
            .getOrNull()

    fun findProjectionByActiveTokenHash(
        tokenHash: String,
        expectedCredentialGeneration: UUID,
    ): SeasonFeedProjectionRow? =
        jdbcClient.sql(
            """
            SELECT
                projection.season_id,
                projection.representation,
                projection.etag,
                projection.last_modified
            FROM calendar_subscription subscription
            JOIN season_feed_projection projection
              ON projection.season_id = subscription.season_id
            WHERE subscription.token_hash = :tokenHash
              AND subscription.status = 'ACTIVE'
              AND subscription.credential_generation = :expectedCredentialGeneration
            """.trimIndent(),
        )
            .param("tokenHash", tokenHash)
            .param("expectedCredentialGeneration", expectedCredentialGeneration)
            .query(SeasonFeedProjectionRow::class.java)
            .optional()
            .getOrNull()

    fun findProjectionMetadataByActiveTokenHash(
        tokenHash: String,
        expectedCredentialGeneration: UUID,
    ): ActiveSeasonFeedProjectionMetadata? =
        jdbcClient.sql(
            """
            SELECT
                projection.season_id,
                projection.etag,
                projection.last_modified
            FROM calendar_subscription subscription
            JOIN season_feed_projection projection
              ON projection.season_id = subscription.season_id
            WHERE subscription.token_hash = :tokenHash
              AND subscription.status = 'ACTIVE'
              AND subscription.credential_generation = :expectedCredentialGeneration
            """.trimIndent(),
        )
            .param("tokenHash", tokenHash)
            .param("expectedCredentialGeneration", expectedCredentialGeneration)
            .query(ActiveSeasonFeedProjectionMetadata::class.java)
            .optional()
            .getOrNull()

    /** 호출자가 읽은 자격 증명이 아직 활성 상태일 때만 회전한다. */
    fun rotate(
        id: UUID,
        expectedTokenHash: String,
        replacementTokenHash: String,
        replacementCredentialGeneration: UUID,
    ): Boolean =
        jdbcClient.sql(
            """
            UPDATE calendar_subscription
            SET token_hash = :replacementTokenHash,
                credential_generation = :replacementCredentialGeneration
            WHERE id = :id
              AND status = 'ACTIVE'
              AND token_hash = :expectedTokenHash
            """.trimIndent(),
        )
            .param("id", id)
            .param("expectedTokenHash", expectedTokenHash)
            .param("replacementTokenHash", replacementTokenHash)
            .param("replacementCredentialGeneration", replacementCredentialGeneration)
            .update() == 1

    fun revoke(
        id: UUID,
        expectedTokenHash: String,
    ): Boolean =
        jdbcClient.sql(
            """
            UPDATE calendar_subscription
            SET status = 'REVOKED'
            WHERE id = :id
              AND token_hash = :expectedTokenHash
            """.trimIndent(),
        )
            .param("id", id)
            .param("expectedTokenHash", expectedTokenHash)
            .update() == 1

    private companion object {
        const val COLUMNS = """
            id,
            season_id,
            token_hash,
            credential_generation,
            status
        """
    }
}
