package io.baton.cal.persistence

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleTimeType
import io.baton.cal.support.PostgreSqlTestContainer
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.jdbc.Sql
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@ImportTestcontainers(PostgreSqlTestContainer::class)
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=persistence-test-internal-token-0001",
    ],
)
@Sql("/reset-database.sql")
class PersistenceRepositoryTest @Autowired constructor(
    private val inboxRepository: SourceEventInboxRepository,
    private val seasonLockRepository: SeasonProjectionLockRepository,
    private val itemRepository: CalendarItemRepository,
    private val feedRepository: SeasonFeedProjectionRepository,
    private val subscriptionRepository: CalendarSubscriptionRepository,
    private val jdbcClient: JdbcClient,
    private val meterRegistry: MeterRegistry,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @Test
    fun `SQL 실행 시간 초과를 Spring 표준 예외로 변환한다`() {
        assertThatThrownBy {
            transaction.executeWithoutResult {
                jdbcClient.sql("SET LOCAL statement_timeout TO '100ms'").update()
                jdbcClient.sql("SELECT pg_sleep(1)").query { _, _ -> true }.single()
            }
        }.isInstanceOf(QueryTimeoutException::class.java)
    }

    @Test
    fun `inbox preserves every envelope while event id remains idempotent`() {
        val row = inboxRow()
        val replay = row.copy(
            eventId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            receivedAt = row.receivedAt.plusSeconds(1),
        )

        assertThat(inboxRepository.insert(row)).isTrue()
        assertThat(inboxRepository.insert(row.copy(payloadHash = HASH_B))).isFalse()
        assertThat(inboxRepository.insert(replay)).isTrue()
        assertThat(inboxRepository.getPayloadHashByEventId(row.eventId)).isEqualTo(row.payloadHash)
        assertThat(inboxRepository.getPayloadHashByEventId(replay.eventId)).isEqualTo(replay.payloadHash)
        assertThat(
            inboxRepository.findPayloadHashBySourceItemIdAndRevision(
                row.sourceItemId,
                row.sourceRevision,
                replay.eventId,
            ),
        ).isEqualTo(row.payloadHash)
    }

    @Test
    fun `calendar item apply classifies stale conflict and forward update`() {
        val original = utcItem(revision = 1)

        assertThat(applyWithSeasonLock(original)).isEqualTo(CalendarItemApplyOutcome.APPLIED)
        assertThat(applyWithSeasonLock(original)).isEqualTo(CalendarItemApplyOutcome.REVISION_CONFLICT)
        assertThat(
            applyWithSeasonLock(
                original.copy(
                    revision = 2,
                    sourceUpdatedAt = original.sourceUpdatedAt,
                ),
            ),
        ).isEqualTo(CalendarItemApplyOutcome.REVISION_CONFLICT)

        val forward = original.copy(
            revision = 3,
            status = CalendarItemStatus.CANCELLED,
            sourceUpdatedAt = Instant.parse("2026-08-11T02:00:00Z"),
            acceptedAt = Instant.parse("2026-08-11T03:00:00Z"),
        )
        assertThat(applyWithSeasonLock(forward)).isEqualTo(CalendarItemApplyOutcome.APPLIED)
        assertThat(applyWithSeasonLock(original.copy(revision = 2)))
            .isEqualTo(CalendarItemApplyOutcome.STALE)
        assertThat(itemRepository.listBySeasonId(SEASON_ID)).containsExactly(forward)
    }

    @Test
    fun `source item cannot move to another season even at a newer revision`() {
        val original = utcItem(revision = 1)
        assertThat(applyWithSeasonLock(original)).isEqualTo(CalendarItemApplyOutcome.APPLIED)

        val moved = original.copy(
            seasonId = OTHER_SEASON_ID,
            revision = 2,
        )
        assertThat(applyWithSeasonLock(moved)).isEqualTo(CalendarItemApplyOutcome.SCOPE_CONFLICT)
        assertThat(itemRepository.listBySeasonId(SEASON_ID)).containsExactly(original)
    }

    @Test
    fun `zoned local rows round trip through persistence`() {
        val later = zonedItem(
            sourceItemId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
        )
        val earlier = zonedItem(
            sourceItemId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        )

        applyWithSeasonLock(later)
        applyWithSeasonLock(earlier)

        assertThat(itemRepository.listBySeasonId(SEASON_ID)).containsExactlyInAnyOrder(earlier, later)
    }

    @Test
    fun `시점 일정과 종일 일정 행을 원본 형태로 읽고 쓴다`() {
        val rows = listOf(
            utcItem(revision = 0).copy(
                sourceItemId = UUID.fromString("10000000-0000-0000-0000-000000000001"),
                timeType = ScheduleTimeType.UTC_POINT,
                startsAtInstant = Instant.parse("2026-09-01T01:02:03Z"),
                endsAtInstant = null,
            ),
            zonedItem(UUID.fromString("20000000-0000-0000-0000-000000000002")).copy(
                timeType = ScheduleTimeType.ZONED_LOCAL_POINT,
                startsAtLocal = LocalDateTime.parse("2026-09-02T18:30:00"),
                endsAtLocal = null,
            ),
            utcItem(revision = 0).copy(
                sourceItemId = UUID.fromString("30000000-0000-0000-0000-000000000003"),
                timeType = ScheduleTimeType.ALL_DAY,
                startsAtInstant = null,
                endsAtInstant = null,
                startsOnDate = LocalDate.parse("2026-09-03"),
                endsOnDate = LocalDate.parse("2026-09-05"),
            ),
        )

        rows.forEach(::applyWithSeasonLock)

        assertThat(itemRepository.listBySeasonId(SEASON_ID)).containsExactlyInAnyOrderElementsOf(rows)
    }

    @Test
    fun `시간 형태 DB 제약은 대표적인 잘못된 행을 거부한다`() {
        val invalidRows = listOf(
            "UTC_POINT의 종료 시각" to utcItem(revision = 0).copy(
                sourceItemId = UUID.fromString("40000000-0000-0000-0000-000000000004"),
                timeType = ScheduleTimeType.UTC_POINT,
            ),
            "ZONED_LOCAL_POINT의 누락된 시간대" to zonedItem(
                UUID.fromString("50000000-0000-0000-0000-000000000005"),
            ).copy(
                timeType = ScheduleTimeType.ZONED_LOCAL_POINT,
                endsAtLocal = null,
                zoneId = null,
            ),
            "ALL_DAY의 동일한 시작일과 종료일" to utcItem(revision = 0).copy(
                sourceItemId = UUID.fromString("60000000-0000-0000-0000-000000000006"),
                timeType = ScheduleTimeType.ALL_DAY,
                startsAtInstant = null,
                endsAtInstant = null,
                startsOnDate = LocalDate.parse("2026-09-03"),
                endsOnDate = LocalDate.parse("2026-09-03"),
            ),
        )

        invalidRows.forEach { (caseName, row) ->
            assertThatThrownBy { applyWithSeasonLock(row) }
                .`as`(caseName)
                .isInstanceOf(DataIntegrityViolationException::class.java)
                .rootCause()
                .hasMessageContaining("ck_calendar_item_time_shape")
        }
    }

    @Test
    fun `materialized feed upsert replaces the season representation and validators`() {
        val initial = SeasonFeedProjectionRow(
            seasonId = SEASON_ID,
            representation = "first".toByteArray(),
            etag = "\"$HASH_A\"",
            lastModified = Instant.parse("2026-08-11T01:00:00Z"),
        )
        val rebuilt = initial.copy(
            representation = "second".toByteArray(),
            etag = "\"$HASH_B\"",
        )

        feedRepository.upsert(initial)
        val initialVersion = projectionVersion()
        feedRepository.upsert(initial.copy(representation = initial.representation.copyOf()))
        assertThat(projectionVersion()).isEqualTo(initialVersion)

        val subscription = subscription()
        subscriptionRepository.insert(subscription)
        feedRepository.upsert(rebuilt)
        assertThat(feedRepository.findMetadataBySeasonId(SEASON_ID)).isEqualTo(
            SeasonFeedProjectionMetadata(
                etag = rebuilt.etag,
                lastModified = rebuilt.lastModified,
            ),
        )
        assertThat(
            subscriptionRepository.findProjectionByActiveTokenHash(
                subscription.tokenHash,
                subscription.credentialGeneration,
            ),
        ).usingRecursiveComparison().isEqualTo(rebuilt)
    }

    @Test
    fun `token rotation invalidates the old hash and revocation invalidates the replacement`() {
        feedRepository.upsert(
            SeasonFeedProjectionRow(
                seasonId = SEASON_ID,
                representation = "feed".toByteArray(),
                etag = "\"$HASH_A\"",
                lastModified = Instant.EPOCH,
            ),
        )
        val subscription = subscription()

        subscriptionRepository.insert(subscription)
        assertThat(subscriptionRepository.findById(subscription.id)).isEqualTo(subscription)
        assertThat(
            subscriptionRepository.findProjectionByActiveTokenHash(
                HASH_A,
                INITIAL_CREDENTIAL_GENERATION,
            ),
        ).isNotNull()
        assertThat(
            subscriptionRepository.findProjectionByActiveTokenHash(
                HASH_A,
                REPLACEMENT_CREDENTIAL_GENERATION,
            ),
        ).isNull()
        assertThat(
            subscriptionRepository.rotate(
                id = subscription.id,
                expectedTokenHash = HASH_A,
                replacementTokenHash = HASH_B,
                replacementCredentialGeneration = REPLACEMENT_CREDENTIAL_GENERATION,
            ),
        ).isTrue()
        assertThat(subscriptionRepository.findById(subscription.id))
            .isEqualTo(
                subscription.copy(
                    tokenHash = HASH_B,
                    credentialGeneration = REPLACEMENT_CREDENTIAL_GENERATION,
                ),
            )
        assertThat(
            subscriptionRepository.findProjectionByActiveTokenHash(
                HASH_A,
                INITIAL_CREDENTIAL_GENERATION,
            ),
        ).isNull()
        assertThat(
            subscriptionRepository.findProjectionByActiveTokenHash(
                HASH_B,
                INITIAL_CREDENTIAL_GENERATION,
            ),
        ).isNull()
        assertThat(
            subscriptionRepository.findProjectionByActiveTokenHash(
                HASH_B,
                REPLACEMENT_CREDENTIAL_GENERATION,
            ),
        ).isNotNull()

        assertThat(
            subscriptionRepository.revoke(
                subscription.id,
                HASH_B,
            ),
        ).isTrue()
        assertThat(subscriptionRepository.findById(subscription.id)?.status)
            .isEqualTo(CalendarSubscriptionStatus.REVOKED)
        assertThat(
            subscriptionRepository.findProjectionByActiveTokenHash(
                HASH_B,
                REPLACEMENT_CREDENTIAL_GENERATION,
            ),
        ).isNull()
        assertThat(
            subscriptionRepository.revoke(
                subscription.id,
                HASH_B,
            ),
        )
            .isTrue()
    }

    @Test
    fun `시즌 투영 잠금은 동시 트랜잭션을 직렬화한다`() {
        val acquisitionCountBefore = meterRegistry
            .get("baton.cal.projection.lock.acquire")
            .timer()
            .count()
        val firstHasLock = CountDownLatch(1)
        val allowFirstToCommit = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val first = executor.submit {
                transaction.executeWithoutResult {
                    seasonLockRepository.acquire(SEASON_ID)
                    firstHasLock.countDown()
                    check(allowFirstToCommit.await(5, TimeUnit.SECONDS))
                }
            }
            assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue()

            assertThatThrownBy {
                transaction.executeWithoutResult {
                    jdbcClient.sql("SET LOCAL lock_timeout TO '200ms'").update()
                    seasonLockRepository.acquire(SEASON_ID)
                }
            }
                .isInstanceOf(CannotAcquireLockException::class.java)

            allowFirstToCommit.countDown()
            first.get(5, TimeUnit.SECONDS)
            transaction.executeWithoutResult {
                seasonLockRepository.acquire(SEASON_ID)
            }
            assertThat(
                meterRegistry.get("baton.cal.projection.lock.acquire").timer().count(),
            ).isEqualTo(acquisitionCountBefore + 3)
        } finally {
            allowFirstToCommit.countDown()
            executor.shutdownNow()
        }
    }

    private fun applyWithSeasonLock(candidate: CalendarItemRow): CalendarItemApplyOutcome =
        checkNotNull(
            transaction.execute {
                seasonLockRepository.acquire(candidate.seasonId)
                itemRepository.applyIfNewer(candidate)
            },
        )

    private fun projectionVersion(): Long = jdbcClient.sql(
        "SELECT xmin::text::bigint FROM season_feed_projection WHERE season_id = :seasonId",
    )
        .param("seasonId", SEASON_ID)
        .query(Long::class.java)
        .single()

    private fun inboxRow() = SourceEventInboxRow(
        eventId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        payloadHash = HASH_A,
        sourceItemId = SOURCE_ITEM_ID,
        seasonId = SEASON_ID,
        sourceRevision = 0,
        occurredAt = Instant.parse("2026-08-11T01:00:00Z"),
        receivedAt = Instant.parse("2026-08-11T01:00:01Z"),
    )

    private fun utcItem(
        revision: Int,
    ) = CalendarItemRow(
        sourceItemId = SOURCE_ITEM_ID,
        seasonId = SEASON_ID,
        revision = revision,
        status = CalendarItemStatus.ACTIVE,
        summary = "Opening",
        description = "First game",
        location = "Seoul",
        timeType = ScheduleTimeType.UTC_INSTANT,
        startsAtInstant = Instant.parse("2026-09-01T01:00:00Z"),
        endsAtInstant = Instant.parse("2026-09-01T02:00:00Z"),
        startsAtLocal = null,
        endsAtLocal = null,
        zoneId = null,
        startsOnDate = null,
        endsOnDate = null,
        sourceUpdatedAt = Instant.parse("2026-08-11T00:30:00Z"),
        acceptedAt = Instant.parse("2026-08-11T01:00:00Z"),
    )

    private fun zonedItem(
        sourceItemId: UUID,
    ) = CalendarItemRow(
        sourceItemId = sourceItemId,
        seasonId = SEASON_ID,
        revision = 0,
        status = CalendarItemStatus.ACTIVE,
        summary = "DST game",
        description = null,
        location = null,
        timeType = ScheduleTimeType.ZONED_LOCAL,
        startsAtInstant = null,
        endsAtInstant = null,
        startsAtLocal = LocalDateTime.parse("2026-11-01T01:30:00"),
        endsAtLocal = LocalDateTime.parse("2026-11-01T02:30:00"),
        zoneId = "America/New_York",
        startsOnDate = null,
        endsOnDate = null,
        sourceUpdatedAt = Instant.parse("2026-10-01T00:00:00Z"),
        acceptedAt = Instant.parse("2026-10-01T00:00:01Z"),
    )

    private fun subscription() = CalendarSubscriptionRow(
        id = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
        seasonId = SEASON_ID,
        tokenHash = HASH_A,
        credentialGeneration = INITIAL_CREDENTIAL_GENERATION,
        status = CalendarSubscriptionStatus.ACTIVE,
    )

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val OTHER_SEASON_ID: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val SOURCE_ITEM_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val INITIAL_CREDENTIAL_GENERATION: UUID =
            UUID.fromString("10000000-0000-0000-0000-000000000001")
        val REPLACEMENT_CREDENTIAL_GENERATION: UUID =
            UUID.fromString("20000000-0000-0000-0000-000000000002")
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
