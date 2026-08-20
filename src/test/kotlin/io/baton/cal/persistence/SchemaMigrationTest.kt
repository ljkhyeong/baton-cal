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
import java.sql.DriverManager

@Testcontainers
class SchemaMigrationTest {
    @Test
    fun `V3와 V4는 기존 구독을 보존하고 자격 증명 세대를 명시 값으로 승격한다`() {
        val before = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .target("2")
            .load()
        before.migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO season_feed_projection
                        (season_id, representation, etag, last_modified, item_count, rebuilt_at)
                    VALUES
                        ('$SEASON_ID', decode('66656564', 'hex'), '\"etag\"', '2026-08-13T00:00:00Z', 0,
                         '2026-08-13T00:00:01Z')
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO calendar_subscription
                        (id, season_id, token_hash, status, created_at, rotated_at, revoked_at)
                    VALUES
                        ('$SUBSCRIPTION_ID', '$SEASON_ID', '$TOKEN_HASH', 'ACTIVE',
                         '2026-08-13T00:00:00Z', NULL, NULL)
                    """.trimIndent(),
                )
            }
        }

        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .target("3")
            .load()
            .migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT season_id, token_hash, status FROM calendar_subscription",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("season_id")).isEqualTo(SEASON_ID)
                    assertThat(rows.getString("token_hash")).isEqualTo(TOKEN_HASH)
                    assertThat(rows.getString("status")).isEqualTo("ACTIVE")
                }
                statement.executeQuery(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND column_name IN ('payload_hash', 'rebuilt_at', 'created_at', 'rotated_at', 'revoked_at')
                      AND table_name IN ('calendar_item', 'season_feed_projection', 'calendar_subscription')
                    """.trimIndent(),
                ).use { rows -> assertThat(rows.next()).isFalse() }
            }
        }

        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()
            .migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT credential_generation FROM calendar_subscription",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("credential_generation"))
                        .isEqualTo(CalProperties.DEFAULT_SUBSCRIPTION_GENERATION.toString())
                }
                statement.executeQuery(
                    """
                    SELECT is_nullable, column_default
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'calendar_subscription'
                      AND column_name = 'credential_generation'
                    """.trimIndent(),
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("is_nullable")).isEqualTo("NO")
                    assertThat(rows.getString("column_default")).isNull()
                }
            }
        }

        val migratedRepository = CalendarSubscriptionRepository(
            JdbcClient.create(
                DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
            ),
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
