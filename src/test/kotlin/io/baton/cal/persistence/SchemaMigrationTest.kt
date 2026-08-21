package io.baton.cal.persistence

import io.baton.cal.config.CalProperties
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

@Testcontainers
class SchemaMigrationTest {
    @Test
    fun `V3부터 V5까지 기존 구독을 보존하고 중복 투영 상태를 제거한다`() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val jdbcClient = JdbcClient.create(dataSource)
        val seasonId = UUID.fromString(SEASON_ID)
        val subscriptionId = UUID.fromString(SUBSCRIPTION_ID)
        val before = Flyway.configure()
            .dataSource(dataSource)
            .target("2")
            .load()
        before.migrate()

        jdbcClient.sql(
            """
            INSERT INTO season_feed_projection
                (season_id, representation, etag, last_modified, item_count, rebuilt_at)
            VALUES
                (:seasonId, decode('66656564', 'hex'), '\"etag\"', '2026-08-13T00:00:00Z', 0,
                 '2026-08-13T00:00:01Z')
            """.trimIndent(),
        )
            .param("seasonId", seasonId)
            .update()
        jdbcClient.sql(
            """
            INSERT INTO calendar_subscription
                (id, season_id, token_hash, status, created_at, rotated_at, revoked_at)
            VALUES
                (:subscriptionId, :seasonId, :tokenHash, 'ACTIVE',
                 '2026-08-13T00:00:00Z', NULL, NULL)
            """.trimIndent(),
        )
            .param("subscriptionId", subscriptionId)
            .param("seasonId", seasonId)
            .param("tokenHash", TOKEN_HASH)
            .update()

        Flyway.configure()
            .dataSource(dataSource)
            .target("3")
            .load()
            .migrate()

        val migratedSubscription = jdbcClient.sql(
            "SELECT season_id, token_hash, status FROM calendar_subscription",
        )
            .query { resultSet, _ ->
                Triple(
                    resultSet.getString("season_id"),
                    resultSet.getString("token_hash"),
                    resultSet.getString("status"),
                )
            }
            .single()
        assertThat(migratedSubscription)
            .isEqualTo(Triple(SEASON_ID, TOKEN_HASH, "ACTIVE"))

        val redundantColumnCount = jdbcClient.sql(
            """
            SELECT count(*)
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND column_name IN ('payload_hash', 'rebuilt_at', 'created_at', 'rotated_at', 'revoked_at')
              AND table_name IN ('calendar_item', 'season_feed_projection', 'calendar_subscription')
            """.trimIndent(),
        )
            .query(Int::class.java)
            .single()
        assertThat(redundantColumnCount).isZero()

        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()

        assertThat(
            jdbcClient.sql("SELECT credential_generation FROM calendar_subscription")
                .query(String::class.java)
                .single(),
        ).isEqualTo(CalProperties.DEFAULT_SUBSCRIPTION_GENERATION.toString())

        val credentialColumn = jdbcClient.sql(
            """
            SELECT is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'calendar_subscription'
              AND column_name = 'credential_generation'
            """.trimIndent(),
        )
            .query { resultSet, _ ->
                resultSet.getString("is_nullable") to resultSet.getString("column_default")
            }
            .single()
        assertThat(credentialColumn.first).isEqualTo("NO")
        assertThat(credentialColumn.second).isNull()

        val itemCountColumnCount = jdbcClient.sql(
            """
            SELECT count(*)
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'season_feed_projection'
              AND column_name = 'item_count'
            """.trimIndent(),
        )
            .query(Int::class.java)
            .single()
        assertThat(itemCountColumnCount).isZero()

        val migratedRepository = CalendarSubscriptionRepository(
            jdbcClient,
        )
        assertThat(
            migratedRepository.findProjectionByActiveTokenHash(
                TOKEN_HASH,
                CalProperties.DEFAULT_SUBSCRIPTION_GENERATION,
            ),
        ).isNotNull()
    }

    private companion object {
        const val SEASON_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val SUBSCRIPTION_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val TOKEN_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18.4-alpine")
    }
}
