package io.baton.cal.snapshot

import io.baton.cal.calendar.CalendarItem
import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.IcsCalendarRenderer
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.calendar.events
import io.baton.cal.calendar.parseIcalendar
import io.baton.cal.calendar.requiredPropertyValue
import io.baton.cal.persistence.SeasonFeedProjectionRepository
import net.fortuna.ical4j.model.Property
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.jdbc.Sql
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

@Testcontainers
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=transaction-recovery-test-token-0001",
    ],
)
@Sql("/reset-database.sql")
class SnapshotTransactionRecoveryTest @Autowired constructor(
    private val ingestionService: SnapshotIngestionService,
    private val projectionRepository: SeasonFeedProjectionRepository,
    private val jdbcClient: JdbcClient,
) {
    @MockitoSpyBean
    lateinit var renderer: IcsCalendarRenderer

    @Test
    fun `render failure rolls back ingest and the same delivery succeeds when retried`() {
        doThrow(SimulatedRenderFailure())
            .doCallRealMethod()
            .`when`(renderer)
            .render(eqArg(SEASON_ID), anyListArg<CalendarItem>())

        assertThatThrownBy { ingestionService.ingest(SNAPSHOT) }
            .isInstanceOf(SimulatedRenderFailure::class.java)

        assertDurableRowCounts(expected = 0L)

        assertThat(ingestionService.ingest(SNAPSHOT)).isEqualTo(SnapshotIngestionResult.APPLIED)
        assertDurableRowCounts(expected = 1L)

        val projection = projectionRepository.findBySeasonId(SEASON_ID)
        assertThat(projection).isNotNull
        assertThat(projection!!.itemCount).isEqualTo(1)
        assertThat(projection.etag).matches("\"[0-9a-f]{64}\"")
        val event = projection.representation.parseIcalendar().events().single()
        assertThat(event.requiredPropertyValue(Property.UID)).isEqualTo("$SOURCE_ITEM_ID@cal.baton")
        assertThat(event.requiredPropertyValue(Property.SUMMARY)).isEqualTo("Recovery fixture")
        verify(renderer, times(2)).render(eqArg(SEASON_ID), anyListArg<CalendarItem>())
    }

    private fun assertDurableRowCounts(expected: Long) {
        assertThat(rowCount("source_event_inbox")).isEqualTo(expected)
        assertThat(rowCount("calendar_item")).isEqualTo(expected)
        assertThat(rowCount("season_feed_projection")).isEqualTo(expected)
    }

    private fun rowCount(table: String): Long = jdbcClient
        .sql("SELECT COUNT(*) FROM $table")
        .query(Long::class.java)
        .single()

    private fun <T> eqArg(value: T): T = ArgumentMatchers.eq(value) ?: value

    private fun <T> anyListArg(): List<T> = ArgumentMatchers.anyList<T>() ?: emptyList()

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

        @Container
        @ServiceConnection
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18.4-alpine")
    }
}
