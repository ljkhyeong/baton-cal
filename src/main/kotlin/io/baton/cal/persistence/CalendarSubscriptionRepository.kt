package io.baton.cal.persistence

import kotlin.jvm.optionals.getOrNull
import java.time.LocalDate
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
                credential_generation,
                status
            ) VALUES (
                :id,
                :seasonId,
                :tokenHash,
                :credentialGeneration,
                :status
            )
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
            .param("id", row.id)
            .param("seasonId", row.seasonId)
            .param("tokenHash", row.tokenHash)
            .param("credentialGeneration", row.credentialGeneration)
            .param("status", row.status.name)
            .update() == 1

    fun findById(id: UUID): CalendarSubscriptionRow? =
        jdbcClient.sql(
            """
            SELECT id, season_id, token_hash, credential_generation, status, last_fetched_on
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
    ): SeasonFeedProjectionRow? = findByActiveTokenHash(
        "projection.season_id, projection.representation, projection.etag, projection.last_modified",
        tokenHash,
        expectedCredentialGeneration,
    )

    fun findProjectionHeadersByActiveTokenHash(
        tokenHash: String,
        expectedCredentialGeneration: UUID,
    ): SeasonFeedHeaders? = findByActiveTokenHash(
        "projection.etag, projection.last_modified, octet_length(projection.representation) AS content_length",
        tokenHash,
        expectedCredentialGeneration,
    )

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
                credential_generation = :replacementCredentialGeneration,
                last_fetched_on = NULL
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
            SET status = 'REVOKED',
                last_fetched_on = NULL
            WHERE id = :id
              AND token_hash = :expectedTokenHash
            """.trimIndent(),
        )
            .param("id", id)
            .param("expectedTokenHash", expectedTokenHash)
            .update() == 1

    /**
     * 공개 피드를 조회한 활성 자격 증명의 최근 조회일을 앞으로만 옮긴다. 같은 날의 반복 조회는 쓰지 않고,
     * 회전·폐기가 잡은 행은 기다리지 않고 건너뛴다. 기록했으면 true다.
     */
    fun recordFetch(
        tokenHash: String,
        expectedCredentialGeneration: UUID,
        fetchedOn: LocalDate,
    ): Boolean =
        jdbcClient.sql(
            """
            UPDATE calendar_subscription
            SET last_fetched_on = :fetchedOn
            WHERE id = (
                SELECT id
                FROM calendar_subscription
                WHERE token_hash = :tokenHash
                  AND status = 'ACTIVE'
                  AND credential_generation = :expectedCredentialGeneration
                  AND (last_fetched_on IS NULL OR last_fetched_on < :fetchedOn)
                FOR UPDATE SKIP LOCKED
            )
            """.trimIndent(),
        )
            .param("tokenHash", tokenHash)
            .param("expectedCredentialGeneration", expectedCredentialGeneration)
            .param("fetchedOn", fetchedOn)
            .update() == 1

    // 공개 피드 조회는 활성 상태·토큰 해시·현재 구독 세대가 모두 일치해야 한다.
    private inline fun <reified T : Any> findByActiveTokenHash(
        columns: String,
        tokenHash: String,
        expectedCredentialGeneration: UUID,
    ): T? =
        jdbcClient.sql(
            """
            SELECT $columns
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
            .query(T::class.java)
            .optional()
            .getOrNull()
}
