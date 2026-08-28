package io.baton.cal.snapshot

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.calendar.parseIcalendar
import io.baton.cal.calendar.requiredEvent
import io.baton.cal.calendar.requiredPropertyValue
import io.baton.cal.persistence.CalendarItemRepository
import io.baton.cal.support.PostgreSqlTestContainer
import net.fortuna.ical4j.model.Property
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.jdbc.Sql
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@ImportTestcontainers(PostgreSqlTestContainer::class)
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=snapshot-concurrency-test-token-0001",
    ],
)
@Sql("/reset-database.sql")
class SnapshotIngestionConcurrencyTest @Autowired constructor(
    private val ingestionService: SnapshotIngestionService,
    private val itemRepository: CalendarItemRepository,
    private val jdbcClient: JdbcClient,
) {
    @Test
    fun `같은 시즌의 연속 개정 번호를 동시에 받아도 최신 항목과 피드가 일치한다`() {
        val revision1 = snapshot(revision = 1)
        val revision2 = snapshot(revision = 2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        val results = Executors.newFixedThreadPool(2).use { executor ->
            val first = executor.submit<SnapshotIngestionResult> {
                ready.countDown()
                start.await()
                ingestionService.ingest(revision1)
            }
            val second = executor.submit<SnapshotIngestionResult> {
                ready.countDown()
                start.await()
                ingestionService.ingest(revision2)
            }

            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            } finally {
                start.countDown()
            }
            first.get(10, TimeUnit.SECONDS) to second.get(10, TimeUnit.SECONDS)
        }

        assertThat(results.second).isEqualTo(SnapshotIngestionResult.APPLIED)
        assertThat(results.first).isIn(SnapshotIngestionResult.APPLIED, SnapshotIngestionResult.STALE)

        val current = itemRepository.listBySeasonId(SEASON_ID).single()
        assertThat(current.revision).isEqualTo(2)
        assertThat(current.summary).isEqualTo("개정 2 일정")

        val projection = jdbcClient.sql(
            "SELECT representation FROM season_feed_projection WHERE season_id = :seasonId",
        )
            .param("seasonId", SEASON_ID)
            .query(ByteArray::class.java)
            .single()
            .parseIcalendar()
            .requiredEvent()
        assertThat(projection.requiredPropertyValue(Property.SEQUENCE)).isEqualTo("2")
        assertThat(projection.requiredPropertyValue(Property.SUMMARY)).isEqualTo("개정 2 일정")
        assertThat(
            jdbcClient.sql("SELECT count(*) FROM source_event_inbox")
                .query(Int::class.java)
                .single(),
        ).isEqualTo(2)
    }

    private fun snapshot(revision: Int) = ScheduleSnapshot(
        eventId = UUID.fromString("10000000-0000-0000-0000-00000000000$revision"),
        occurredAt = Instant.parse("2026-08-28T00:00:0${revision}Z"),
        sourceItemId = SOURCE_ITEM_ID,
        seasonId = SEASON_ID,
        revision = revision,
        status = CalendarItemStatus.ACTIVE,
        summary = "개정 $revision 일정",
        description = null,
        location = null,
        schedule = ScheduleWindow.UtcInstant(
            start = Instant.parse("2026-09-01T01:00:00Z"),
            end = Instant.parse("2026-09-01T02:00:00Z"),
        ),
        sourceUpdatedAt = Instant.parse("2026-08-28T00:00:0${revision}Z"),
    )

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val SOURCE_ITEM_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    }
}
