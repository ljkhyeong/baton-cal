package io.baton.cal.projection

import io.baton.cal.support.PostgreSqlTestContainer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.jdbc.Sql
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.measureTimedValue

@Tag("load")
@ImportTestcontainers(PostgreSqlTestContainer::class)
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=projection-load-test-token-that-is-long-enough",
    ],
)
@Sql("/reset-database.sql")
class SeasonProjectionLoadTest @Autowired constructor(
    private val projectionService: SeasonProjectionService,
    private val jdbcTemplate: JdbcTemplate,
    private val jdbcClient: JdbcClient,
) {
    @Test
    fun `시즌 항목 수별 전체 투영 재구축 시간을 측정한다`() {
        ITEM_COUNTS.forEach { itemCount ->
            val seasonId = UUID(0, itemCount.toLong())
            insertItems(seasonId, itemCount)

            val measured = measureTimedValue { projectionService.rebuild(seasonId) }
            val representationBytes = jdbcClient.sql(
                "SELECT octet_length(representation) FROM season_feed_projection WHERE season_id = :seasonId",
            )
                .param("seasonId", seasonId)
                .query(Int::class.java)
                .single()

            assertThat(measured.value.itemCount).isEqualTo(itemCount)
            assertThat(representationBytes).isPositive()
            println(
                "BATON_CAL_PROJECTION_LOAD items=$itemCount " +
                    "duration_ms=${measured.duration.inWholeMilliseconds} bytes=$representationBytes",
            )
        }
    }

    private fun insertItems(seasonId: UUID, itemCount: Int) {
        jdbcTemplate.batchUpdate(
            INSERT_ITEM_SQL,
            (0 until itemCount).toList(),
            INSERT_BATCH_SIZE,
        ) { statement, index ->
            statement.setObject(1, UUID(itemCount.toLong(), index.toLong()))
            statement.setObject(2, seasonId)
            statement.setString(3, "일정 $index")
            statement.setObject(4, START.atOffset(ZoneOffset.UTC))
            statement.setObject(5, END.atOffset(ZoneOffset.UTC))
            statement.setObject(6, SOURCE_UPDATED_AT.atOffset(ZoneOffset.UTC))
            statement.setObject(7, ACCEPTED_AT.atOffset(ZoneOffset.UTC))
        }
    }

    private companion object {
        val ITEM_COUNTS = listOf(500, 1_000, 5_000, 10_000)
        const val INSERT_BATCH_SIZE = 1_000
        val START: Instant = Instant.parse("2026-09-01T01:00:00Z")
        val END: Instant = Instant.parse("2026-09-01T02:00:00Z")
        val SOURCE_UPDATED_AT: Instant = Instant.parse("2026-08-28T00:00:00Z")
        val ACCEPTED_AT: Instant = Instant.parse("2026-08-28T00:00:01Z")
        val INSERT_ITEM_SQL = """
            INSERT INTO calendar_item (
                source_item_id,
                season_id,
                revision,
                status,
                summary,
                time_type,
                starts_at_instant,
                ends_at_instant,
                source_updated_at,
                accepted_at
            ) VALUES (?, ?, 1, 'ACTIVE', ?, 'UTC_INSTANT', ?, ?, ?, ?)
        """.trimIndent()
    }
}
