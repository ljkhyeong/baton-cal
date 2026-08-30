package io.baton.cal.persistence

import io.baton.cal.config.CalProperties
import io.baton.cal.support.PostgreSqlTestContainer
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
    fun `V3부터 V6까지 기존 구독과 일정 표현을 보존한다`() {
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
        jdbcClient.sql(
            """
            INSERT INTO calendar_item
                (source_item_id, season_id, revision, payload_hash, status, summary, description, location,
                 time_type, starts_at_instant, ends_at_instant, starts_at_local, ends_at_local,
                 zone_id, source_updated_at, accepted_at)
            VALUES
                (:sourceItemId, :seasonId, 4, :payloadHash, 'ACTIVE', '기존 구간 일정', NULL, NULL,
                 'UTC_INSTANT', '2026-09-01T01:00:00Z', '2026-09-01T02:00:00Z', NULL, NULL,
                 NULL, '2026-08-13T00:00:00Z', '2026-08-13T00:00:01Z')
            """.trimIndent(),
        )
            .param("sourceItemId", UUID.fromString(SOURCE_ITEM_ID))
            .param("seasonId", seasonId)
            .param("payloadHash", TOKEN_HASH)
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

        val itemAfterV3 = jdbcClient.sql(
            """
            SELECT revision, status, summary
            FROM calendar_item
            WHERE source_item_id = :sourceItemId
            """.trimIndent(),
        )
            .param("sourceItemId", UUID.fromString(SOURCE_ITEM_ID))
            .query { resultSet, _ ->
                Triple(
                    resultSet.getInt("revision"),
                    resultSet.getString("status"),
                    resultSet.getString("summary"),
                )
            }
            .single()
        assertThat(itemAfterV3).isEqualTo(Triple(4, "ACTIVE", "기존 구간 일정"))

        Flyway.configure()
            .dataSource(dataSource)
            .target("5")
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

        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()

        val migratedItem = jdbcClient.sql(
            """
            SELECT time_type, starts_on_date, ends_on_date
            FROM calendar_item
            WHERE source_item_id = :sourceItemId
            """.trimIndent(),
        )
            .param("sourceItemId", UUID.fromString(SOURCE_ITEM_ID))
            .query { resultSet, _ ->
                Triple(
                    resultSet.getString("time_type"),
                    resultSet.getObject("starts_on_date"),
                    resultSet.getObject("ends_on_date"),
                )
            }
            .single()
        assertThat(migratedItem).isEqualTo(Triple("UTC_INSTANT", null, null))

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
        const val SOURCE_ITEM_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val TOKEN_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        @Container
        @JvmField
        val postgres = PostgreSQLContainer(PostgreSqlTestContainer.IMAGE)
    }
}
