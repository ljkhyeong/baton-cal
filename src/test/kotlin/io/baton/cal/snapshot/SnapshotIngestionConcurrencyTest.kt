package io.baton.cal.snapshot

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.calendar.events
import io.baton.cal.calendar.parseIcalendar
import io.baton.cal.calendar.requiredPropertyValue
import io.baton.cal.persistence.CalendarItemRepository
import io.baton.cal.persistence.SeasonProjectionLockRepository
import io.baton.cal.support.PostgreSqlTestContainer
import net.fortuna.ical4j.model.Property
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.util.AopTestUtils
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    @MockitoSpyBean
    private lateinit var lockRepository: SeasonProjectionLockRepository

    @Test
    fun `같은 시즌의 서로 다른 항목을 동시에 받아도 피드에 모두 남는다`() {
        synchronizeSeasonLockAcquisition()
        val firstSnapshot = snapshot(number = 1)
        val secondSnapshot = snapshot(number = 2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        val results = Executors.newFixedThreadPool(2).use { executor ->
            val first = executor.submit<SnapshotIngestionResult> {
                ready.countDown()
                start.await()
                ingestionService.ingest(firstSnapshot)
            }
            val second = executor.submit<SnapshotIngestionResult> {
                ready.countDown()
                start.await()
                ingestionService.ingest(secondSnapshot)
            }

            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            } finally {
                start.countDown()
            }
            listOf(
                first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        }

        assertThat(results).containsOnly(SnapshotIngestionResult.APPLIED)
        assertThat(itemRepository.listBySeasonId(SEASON_ID).map { it.sourceItemId })
            .containsExactlyInAnyOrder(firstSnapshot.sourceItemId, secondSnapshot.sourceItemId)

        val events = jdbcClient.sql(
            "SELECT representation FROM season_feed_projection WHERE season_id = :seasonId",
        )
            .param("seasonId", SEASON_ID)
            .query(ByteArray::class.java)
            .single()
            .parseIcalendar()
            .events()
        assertThat(events.map { it.requiredPropertyValue(Property.UID) })
            .containsExactlyInAnyOrder(
                "${firstSnapshot.sourceItemId}@cal.baton",
                "${secondSnapshot.sourceItemId}@cal.baton",
            )
        assertThat(
            jdbcClient.sql("SELECT count(*) FROM source_event_inbox")
                .query(Int::class.java)
                .single(),
        ).isEqualTo(2)
    }

    private fun synchronizeSeasonLockAcquisition() {
        val firstHasLock = CountDownLatch(1)
        val secondReachedLock = CountDownLatch(1)
        val acquisitionCount = AtomicInteger()
        val lockTarget = AopTestUtils.getUltimateTargetObject<SeasonProjectionLockRepository>(lockRepository)

        doAnswer { invocation ->
            when (acquisitionCount.incrementAndGet()) {
                1 -> {
                    invocation.callRealMethod()
                    firstHasLock.countDown()
                    check(secondReachedLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    null
                }

                2 -> {
                    check(firstHasLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    secondReachedLock.countDown()
                    invocation.callRealMethod()
                }

                else -> invocation.callRealMethod()
            }
        }.`when`(lockTarget).acquire(SEASON_ID)
    }

    private fun snapshot(number: Int) = ScheduleSnapshot(
        eventId = UUID.fromString("10000000-0000-0000-0000-00000000000$number"),
        occurredAt = Instant.parse("2026-08-28T00:00:0${number}Z"),
        sourceItemId = UUID.fromString("20000000-0000-0000-0000-00000000000$number"),
        seasonId = SEASON_ID,
        revision = 1,
        status = CalendarItemStatus.ACTIVE,
        summary = "항목 $number 일정",
        description = null,
        location = null,
        schedule = ScheduleWindow.UtcInstant(
            start = Instant.parse("2026-09-01T01:00:00Z"),
            end = Instant.parse("2026-09-01T02:00:00Z"),
        ),
        sourceUpdatedAt = Instant.parse("2026-08-28T00:00:0${number}Z"),
    )

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        val SEASON_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    }
}
