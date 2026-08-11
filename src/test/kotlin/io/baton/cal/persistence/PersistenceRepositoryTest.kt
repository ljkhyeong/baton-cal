package io.baton.cal.persistence

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleTimeType
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=persistence-test-internal-token-0001",
    ],
)
class PersistenceRepositoryTest @Autowired constructor(
    private val inboxRepository: SourceEventInboxRepository,
    private val seasonLockRepository: SeasonProjectionLockRepository,
    private val itemRepository: CalendarItemRepository,
    private val feedRepository: SeasonFeedProjectionRepository,
    private val subscriptionRepository: CalendarSubscriptionRepository,
    private val jdbcClient: JdbcClient,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @BeforeEach
    fun resetDatabase() {
        jdbcClient.sql(
            """
            TRUNCATE TABLE
                calendar_subscription,
                season_feed_projection,
                calendar_item,
                source_event_inbox,
                season_projection_lock
            """.trimIndent(),
        ).update()
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
        assertThat(inboxRepository.findByEventId(row.eventId)).isEqualTo(row)
        assertThat(inboxRepository.findByEventId(replay.eventId)).isEqualTo(replay)
        assertThat(inboxRepository.findBySourceItemIdAndRevision(row.sourceItemId, row.sourceRevision))
            .isEqualTo(row)
    }

    @Test
    fun `calendar item apply classifies duplicate stale conflict and forward update`() {
        val original = utcItem(revision = 1, payloadHash = HASH_A)

        assertThat(applyWithSeasonLock(original).outcome).isEqualTo(CalendarItemApplyOutcome.APPLIED)
        assertThat(applyWithSeasonLock(original).outcome).isEqualTo(CalendarItemApplyOutcome.DUPLICATE)
        assertThat(
            applyWithSeasonLock(original.copy(payloadHash = HASH_B, summary = "conflicting payload")).outcome,
        ).isEqualTo(CalendarItemApplyOutcome.CONFLICT)
        assertThat(
            applyWithSeasonLock(
                original.copy(
                    revision = 2,
                    payloadHash = HASH_B,
                    sourceUpdatedAt = original.sourceUpdatedAt,
                ),
            ).outcome,
        ).isEqualTo(CalendarItemApplyOutcome.CONFLICT)

        val forward = original.copy(
            revision = 3,
            payloadHash = HASH_C,
            status = CalendarItemStatus.CANCELLED,
            sourceUpdatedAt = Instant.parse("2026-08-11T02:00:00Z"),
            acceptedAt = Instant.parse("2026-08-11T03:00:00Z"),
        )
        assertThat(applyWithSeasonLock(forward).outcome).isEqualTo(CalendarItemApplyOutcome.APPLIED)
        assertThat(applyWithSeasonLock(original.copy(revision = 2, payloadHash = HASH_D)).outcome)
            .isEqualTo(CalendarItemApplyOutcome.STALE)
        assertThat(itemRepository.findBySourceItemId(original.sourceItemId)).isEqualTo(forward)
    }

    @Test
    fun `source item cannot move to another season even at a newer revision`() {
        val original = utcItem(revision = 1, payloadHash = HASH_A)
        assertThat(applyWithSeasonLock(original).outcome).isEqualTo(CalendarItemApplyOutcome.APPLIED)

        val moved = original.copy(
            seasonId = OTHER_SEASON_ID,
            revision = 2,
            payloadHash = HASH_B,
        )
        assertThat(applyWithSeasonLock(moved).outcome).isEqualTo(CalendarItemApplyOutcome.CONFLICT)
        assertThat(itemRepository.findBySourceItemId(original.sourceItemId)).isEqualTo(original)
    }

    @Test
    fun `zoned local rows round trip and season listing has stable source id order`() {
        val later = zonedItem(
            sourceItemId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
            payloadHash = HASH_A,
        )
        val earlier = zonedItem(
            sourceItemId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            payloadHash = HASH_B,
        )

        applyWithSeasonLock(later)
        applyWithSeasonLock(earlier)

        assertThat(itemRepository.listBySeasonId(SEASON_ID)).containsExactly(earlier, later)
    }

    @Test
    fun `materialized feed upsert replaces the season representation and validators`() {
        val initial = SeasonFeedProjectionRow(
            seasonId = SEASON_ID,
            representation = "first".toByteArray(),
            etag = "\"$HASH_A\"",
            lastModified = Instant.parse("2026-08-11T01:00:00Z"),
            itemCount = 1,
            rebuiltAt = Instant.parse("2026-08-11T01:01:00Z"),
        )
        val rebuilt = initial.copy(
            representation = "second".toByteArray(),
            etag = "\"$HASH_B\"",
            itemCount = 2,
            rebuiltAt = Instant.parse("2026-08-11T02:00:00Z"),
        )

        assertThat(feedRepository.upsert(initial)).usingRecursiveComparison().isEqualTo(initial)
        assertThat(feedRepository.upsert(rebuilt)).usingRecursiveComparison().isEqualTo(rebuilt)
        assertThat(feedRepository.findBySeasonId(SEASON_ID)).usingRecursiveComparison().isEqualTo(rebuilt)
    }

    @Test
    fun `token rotation invalidates the old hash and revocation invalidates the replacement`() {
        val subscription = CalendarSubscriptionRow(
            id = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
            seasonId = SEASON_ID,
            tokenHash = HASH_A,
            status = CalendarSubscriptionStatus.ACTIVE,
            createdAt = Instant.parse("2026-08-11T01:00:00Z"),
            rotatedAt = null,
            revokedAt = null,
        )

        assertThat(subscriptionRepository.insert(subscription)).isTrue()
        assertThat(subscriptionRepository.findActiveByTokenHash(HASH_A)).isEqualTo(subscription)
        assertThat(
            subscriptionRepository.rotate(
                id = subscription.id,
                expectedTokenHash = HASH_A,
                replacementTokenHash = HASH_B,
                rotatedAt = Instant.parse("2026-08-11T02:00:00Z"),
            ),
        ).isTrue()
        assertThat(subscriptionRepository.findActiveByTokenHash(HASH_A)).isNull()
        assertThat(subscriptionRepository.findActiveByTokenHash(HASH_B)).isNotNull()

        assertThat(
            subscriptionRepository.revoke(
                subscription.id,
                HASH_B,
                Instant.parse("2026-08-11T03:00:00Z"),
            ),
        ).isTrue()
        assertThat(subscriptionRepository.findActiveByTokenHash(HASH_B)).isNull()
        assertThat(subscriptionRepository.findById(subscription.id)?.status)
            .isEqualTo(CalendarSubscriptionStatus.REVOKED)
        assertThat(
            subscriptionRepository.revoke(
                subscription.id,
                HASH_B,
                Instant.parse("2026-08-11T04:00:00Z"),
            ),
        )
            .isFalse()
    }

    @Test
    fun `season projection lock serializes concurrent projection transactions`() {
        val firstHasLock = CountDownLatch(1)
        val allowFirstToCommit = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondHasLock = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit {
                transaction.executeWithoutResult {
                    seasonLockRepository.acquire(SEASON_ID)
                    firstHasLock.countDown()
                    check(allowFirstToCommit.await(5, TimeUnit.SECONDS))
                }
            }
            assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue()

            val second = executor.submit {
                secondStarted.countDown()
                transaction.executeWithoutResult {
                    seasonLockRepository.acquire(SEASON_ID)
                    secondHasLock.countDown()
                }
            }
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(secondHasLock.await(250, TimeUnit.MILLISECONDS)).isFalse()

            allowFirstToCommit.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertThat(secondHasLock.await(5, TimeUnit.SECONDS)).isTrue()
            second.get(5, TimeUnit.SECONDS)
        } finally {
            allowFirstToCommit.countDown()
            executor.shutdownNow()
        }
    }

    private fun applyWithSeasonLock(candidate: CalendarItemRow): CalendarItemApplyResult =
        checkNotNull(
            transaction.execute {
                seasonLockRepository.acquire(candidate.seasonId)
                itemRepository.applyIfNewer(candidate)
            },
        )

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
        payloadHash: String,
    ) = CalendarItemRow(
        sourceItemId = SOURCE_ITEM_ID,
        seasonId = SEASON_ID,
        revision = revision,
        payloadHash = payloadHash,
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
        sourceUpdatedAt = Instant.parse("2026-08-11T00:30:00Z"),
        acceptedAt = Instant.parse("2026-08-11T01:00:00Z"),
    )

    private fun zonedItem(
        sourceItemId: UUID,
        payloadHash: String,
    ) = CalendarItemRow(
        sourceItemId = sourceItemId,
        seasonId = SEASON_ID,
        revision = 0,
        payloadHash = payloadHash,
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
        sourceUpdatedAt = Instant.parse("2026-10-01T00:00:00Z"),
        acceptedAt = Instant.parse("2026-10-01T00:00:01Z"),
    )

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val OTHER_SEASON_ID: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val SOURCE_ITEM_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"

        @Container
        @ServiceConnection
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18.4-alpine")
    }
}
