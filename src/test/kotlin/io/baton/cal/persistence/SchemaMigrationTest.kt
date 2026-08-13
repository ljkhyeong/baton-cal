package io.baton.cal.persistence

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

@Testcontainers
class SchemaMigrationTest {
    @Test
    fun `V3는 기존 데이터와 구독 동작을 보존하며 중복 상태를 제거한다`() {
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
