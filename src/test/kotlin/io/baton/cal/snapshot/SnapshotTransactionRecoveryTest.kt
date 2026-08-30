package io.baton.cal.snapshot

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.IcsCalendarRenderer
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.calendar.parseIcalendar
import io.baton.cal.calendar.requiredEvent
import io.baton.cal.calendar.requiredPropertyValue
import io.baton.cal.support.PostgreSqlTestContainer
import net.fortuna.ical4j.model.Property
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.jdbc.JdbcTestUtils
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.jdbc.Sql
import java.time.Instant
import java.util.UUID

@ImportTestcontainers(PostgreSqlTestContainer::class)
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=transaction-recovery-test-token-0001",
    ],
)
@Sql("/reset-database.sql")
class SnapshotTransactionRecoveryTest @Autowired constructor(
    private val ingestionService: SnapshotIngestionService,
    private val jdbcClient: JdbcClient,
) {
    @MockitoSpyBean
    lateinit var renderer: IcsCalendarRenderer

    @Test
    fun `render failure rolls back ingest and the same delivery succeeds when retried`() {
        doThrow(SimulatedRenderFailure())
            .doCallRealMethod()
            .`when`(renderer)
            .render(eqArg(SEASON_ID), ArgumentMatchers.anyList())

        assertThatThrownBy { ingestionService.ingest(SNAPSHOT) }
            .isInstanceOf(SimulatedRenderFailure::class.java)

        assertDurableRowCounts(expected = 0)

        assertThat(ingestionService.ingest(SNAPSHOT)).isEqualTo(SnapshotIngestionResult.APPLIED)
        assertDurableRowCounts(expected = 1)

        val projection = jdbcClient.sql(
            "SELECT representation FROM season_feed_projection WHERE season_id = :seasonId",
        )
            .param("seasonId", SEASON_ID)
            .query(ByteArray::class.java)
            .single()
        val event = projection.parseIcalendar().requiredEvent()
        assertThat(event.requiredPropertyValue(Property.UID)).isEqualTo("$SOURCE_ITEM_ID@cal.baton")
        assertThat(event.requiredPropertyValue(Property.SUMMARY)).isEqualTo("Recovery fixture")
    }

    private fun assertDurableRowCounts(expected: Int) {
        assertThat(JdbcTestUtils.countRowsInTable(jdbcClient, "source_event_inbox")).isEqualTo(expected)
        assertThat(JdbcTestUtils.countRowsInTable(jdbcClient, "calendar_item")).isEqualTo(expected)
        assertThat(JdbcTestUtils.countRowsInTable(jdbcClient, "season_feed_projection")).isEqualTo(expected)
    }

    private fun <T> eqArg(value: T): T = ArgumentMatchers.eq(value) ?: value

    private class SimulatedRenderFailure : RuntimeException()

    companion object {
        val SEASON_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val SOURCE_ITEM_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

        val SNAPSHOT = ScheduleSnapshot(
            eventId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            occurredAt = Instant.parse("2026-08-13T01:00:00Z"),
            sourceItemId = SOURCE_ITEM_ID,
            seasonId = SEASON_ID,
            revision = 0,
            status = CalendarItemStatus.ACTIVE,
            summary = "Recovery fixture",
            description = "The retry must rebuild the durable projection",
            location = "Seoul",
            schedule = ScheduleWindow.UtcInstant(
                start = Instant.parse("2026-09-01T01:00:00Z"),
                end = Instant.parse("2026-09-01T02:00:00Z"),
            ),
            sourceUpdatedAt = Instant.parse("2026-08-13T00:30:00Z"),
        )

    }
}
