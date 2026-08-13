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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.jdbc.Sql
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
@Sql("/reset-database.sql")
class PersistenceRepositoryTest @Autowired constructor(
    private val inboxRepository: SourceEventInboxRepository,
    private val seasonLockRepository: SeasonProjectionLockRepository,
    private val itemRepository: CalendarItemRepository,
    private val feedRepository: SeasonFeedProjectionRepository,
    private val subscriptionRepository: CalendarSubscriptionRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

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
        assertThat(inboxRepository.findPayloadHashByEventId(row.eventId)).isEqualTo(row.payloadHash)
        assertThat(inboxRepository.findPayloadHashByEventId(replay.eventId)).isEqualTo(replay.payloadHash)
        assertThat(
            inboxRepository.findPayloadHashBySourceItemIdAndRevision(row.sourceItemId, row.sourceRevision),
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
    fun `materialized feed upsert replaces the season representation and validators`() {
        val initial = SeasonFeedProjectionRow(
            seasonId = SEASON_ID,
            representation = "first".toByteArray(),
            etag = "\"$HASH_A\"",
            lastModified = Instant.parse("2026-08-11T01:00:00Z"),
            itemCount = 1,
        )
        val rebuilt = initial.copy(
            representation = "second".toByteArray(),
            etag = "\"$HASH_B\"",
            itemCount = 2,
        )

        feedRepository.upsert(initial)
        feedRepository.upsert(rebuilt)
        assertThat(feedRepository.findBySeasonId(SEASON_ID)).usingRecursiveComparison().isEqualTo(rebuilt)
    }

    @Test
    fun `token rotation invalidates the old hash and revocation invalidates the replacement`() {
        feedRepository.upsert(
            SeasonFeedProjectionRow(
                seasonId = SEASON_ID,
                representation = "feed".toByteArray(),
                etag = "\"$HASH_A\"",
                lastModified = Instant.EPOCH,
                itemCount = 0,
            ),
        )
        val subscription = CalendarSubscriptionRow(
            id = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
            seasonId = SEASON_ID,
            tokenHash = HASH_A,
            status = CalendarSubscriptionStatus.ACTIVE,
        )

        subscriptionRepository.insert(subscription)
        assertThat(subscriptionRepository.findById(subscription.id)).isEqualTo(subscription)
        assertThat(
            subscriptionRepository.rotate(
                id = subscription.id,
                expectedTokenHash = HASH_A,
                replacementTokenHash = HASH_B,
            ),
        ).isTrue()
        assertThat(subscriptionRepository.findById(subscription.id)?.tokenHash).isEqualTo(HASH_B)

        assertThat(
            subscriptionRepository.revoke(
                subscription.id,
                HASH_B,
            ),
        ).isTrue()
        assertThat(subscriptionRepository.findById(subscription.id)?.status)
            .isEqualTo(CalendarSubscriptionStatus.REVOKED)
        assertThat(
            subscriptionRepository.revoke(
                subscription.id,
                HASH_B,
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

    private fun applyWithSeasonLock(candidate: CalendarItemRow): CalendarItemApplyOutcome =
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
