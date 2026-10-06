package io.baton.cal.persistence

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleTimeType
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.recovery.RecoverySeasonState
import io.baton.cal.snapshot.ScheduleSnapshot
import io.baton.cal.support.CalIntegrationTest
import io.baton.cal.support.whileLocked
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@CalIntegrationTest
class PersistenceRepositoryTest @Autowired constructor(
    private val lockRepository: AdvisoryLockRepository,
    private val itemRepository: CalendarItemRepository,
    private val feedRepository: SeasonFeedProjectionRepository,
    private val subscriptionRepository: CalendarSubscriptionRepository,
    private val recoveryRepository: RecoveryManifestRepository,
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
    fun `모든 시간 형태의 스냅샷을 행으로 저장하고 원래 시간 형태로 읽는다`() {
        val schedules = listOf(
            UTC_INSTANT,
            ScheduleWindow.UtcPoint(Instant.parse("2026-09-01T01:02:03Z")),
            ScheduleWindow.ZonedLocal(
                start = LocalDateTime.parse("2026-11-01T01:30:00"),
                end = LocalDateTime.parse("2026-11-01T02:30:00"),
                zoneId = "America/New_York",
            ),
            ScheduleWindow.ZonedLocalPoint(LocalDateTime.parse("2026-09-02T18:30:00"), "America/New_York"),
            ScheduleWindow.AllDay(LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-05")),
        )
        val rows = schedules.mapIndexed { index, schedule -> row(index, schedule) }

        rows.forEach { assertThat(upsertWithSeasonLock(it)).isTrue() }

        val stored = itemRepository.listBySeasonId(SEASON_ID)
        assertThat(stored).containsExactlyInAnyOrderElementsOf(rows)
        assertThat(stored.map { it.toCalendarItem().schedule }).containsExactlyInAnyOrderElementsOf(schedules)
    }

    @Test
    fun `시간 형태 DB 제약은 대표적인 잘못된 행을 거부한다`() {
        val utc = row(0, UTC_INSTANT)
        val invalidRows = listOf(
            "UTC_POINT의 종료 시각" to utc.copy(timeType = ScheduleTimeType.UTC_POINT),
            "UTC_INSTANT에 섞인 현지 시각" to utc.copy(startsAtLocal = LocalDateTime.parse("2026-09-01T10:00:00")),
            "ZONED_LOCAL_POINT의 누락된 시간대" to utc.copy(
                timeType = ScheduleTimeType.ZONED_LOCAL_POINT,
                startsAtInstant = null,
                endsAtInstant = null,
                startsAtLocal = LocalDateTime.parse("2026-09-02T18:30:00"),
            ),
            "ALL_DAY의 동일한 시작일과 종료일" to utc.copy(
                timeType = ScheduleTimeType.ALL_DAY,
                startsAtInstant = null,
                endsAtInstant = null,
                startsOnDate = LocalDate.parse("2026-09-03"),
                endsOnDate = LocalDate.parse("2026-09-03"),
            ),
        )

        invalidRows.forEach { (caseName, row) ->
            assertThatThrownBy { upsertWithSeasonLock(row) }
                .`as`(caseName)
                .isInstanceOf(DataIntegrityViolationException::class.java)
                .rootCause()
                .hasMessageContaining("ck_calendar_item_time_shape")
        }
        assertThatThrownBy {
            jdbcClient.sql(
                """
                INSERT INTO calendar_item (
                    source_item_id, season_id, revision, status, summary, time_type,
                    starts_at_instant, ends_at_instant, source_updated_at, accepted_at
                ) VALUES (
                    gen_random_uuid(), :seasonId, 0, 'ACTIVE', '일정', 'UNKNOWN',
                    now(), now() + interval '1 hour', now(), now()
                )
                """.trimIndent(),
            ).param("seasonId", SEASON_ID).update()
        }
            .`as`("알 수 없는 시간 형태")
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .rootCause()
            .hasMessageContaining("ck_calendar_item_time_shape")
    }

    @Test
    fun `복구 매니페스트는 시즌 이름 개정 번호와 다이제스트 중 하나만 저장하지 않는다`() {
        val state = RecoverySeasonState(SEASON_ID, 0, HASH_A, 1, HASH_B)

        listOf(state.copy(metadataDigest = null), state.copy(metadataRevision = null)).forEach { invalid ->
            assertThatThrownBy { recoveryRepository.upsertSeasonManifest(RECOVERY_ID, invalid, Instant.EPOCH) }
                .isInstanceOf(DataIntegrityViolationException::class.java)
                .rootCause()
                .hasMessageContaining("ck_recovery_season_manifest_metadata")
        }
        assertThat(recoveryRepository.listVerifiedSeasonStates(RECOVERY_ID)).isEmpty()
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
            representation = "수정한 일정".toByteArray(),
            etag = "\"$HASH_B\"",
        )

        feedRepository.upsert(initial)
        val initialVersion = projectionVersion()
        feedRepository.upsert(initial.copy(representation = initial.representation.copyOf()))
        assertThat(projectionVersion()).isEqualTo(initialVersion)

        val subscription = subscription()
        subscriptionRepository.insert(subscription)
        feedRepository.upsert(rebuilt)
        assertThat(feedRepository.findHeadersBySeasonId(SEASON_ID)).isEqualTo(
            SeasonFeedHeaders(
                etag = rebuilt.etag,
                lastModified = rebuilt.lastModified,
                contentLength = rebuilt.representation.size,
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
    fun `이미 폐기한 구독을 같은 해시로 다시 폐기해도 성공한다`() {
        // 동시 DELETE 두 건이 모두 ACTIVE를 읽었을 때 뒤 요청도 멱등하게 성공하도록 상태 조건 없이 갱신한다.
        feedRepository.upsert(SeasonFeedProjectionRow(SEASON_ID, "feed".toByteArray(), "\"$HASH_A\"", Instant.EPOCH))
        val subscription = subscription()
        subscriptionRepository.insert(subscription)

        repeat(2) { assertThat(subscriptionRepository.revoke(subscription.id, HASH_A)).isTrue() }
        assertThat(subscriptionRepository.findById(subscription.id)?.status)
            .isEqualTo(CalendarSubscriptionStatus.REVOKED)
    }

    @Test
    fun `시즌 잠금은 동시 트랜잭션을 직렬화한다`() {
        val acquisitions = meterRegistry.get("baton.cal.projection.lock.acquire").timer()
        val acquisitionCountBefore = acquisitions.count()

        transaction.whileLocked(jdbcClient, lock = { lockRepository.lockSeason(SEASON_ID) }) {
            assertThatThrownBy { lockRepository.lockSeason(SEASON_ID) }
                .isInstanceOf(CannotAcquireLockException::class.java)
        }
        transaction.executeWithoutResult { lockRepository.lockSeason(SEASON_ID) }

        assertThat(acquisitions.count()).isEqualTo(acquisitionCountBefore + 3)
    }

    private fun upsertWithSeasonLock(row: CalendarItemRow): Boolean = checkNotNull(
        transaction.execute {
            lockRepository.lockSeason(row.seasonId)
            itemRepository.upsertIfNewer(row)
        },
    )

    private fun projectionVersion(): Long = jdbcClient.sql(
        "SELECT xmin::text::bigint FROM season_feed_projection WHERE season_id = :seasonId",
    )
        .param("seasonId", SEASON_ID)
        .query(Long::class.java)
        .single()

    private fun row(index: Int, schedule: ScheduleWindow) = CalendarItemRow.from(
        ScheduleSnapshot(
            eventId = UUID(1, index.toLong()),
            sourceItemId = UUID(0, index.toLong()),
            seasonId = SEASON_ID,
            revision = 0,
            status = CalendarItemStatus.ACTIVE,
            summary = "일정 $index",
            description = "설명",
            location = null,
            schedule = schedule,
            sourceUpdatedAt = Instant.parse("2026-08-11T00:30:00Z"),
        ),
        acceptedAt = Instant.parse("2026-08-11T01:00:00Z"),
    )

    private fun subscription() = CalendarSubscriptionRow(
        id = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
        seasonId = SEASON_ID,
        tokenHash = HASH_A,
        credentialGeneration = UUID.fromString("10000000-0000-0000-0000-000000000001"),
        status = CalendarSubscriptionStatus.ACTIVE,
    )

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val RECOVERY_ID: UUID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
        val UTC_INSTANT = ScheduleWindow.UtcInstant(
            start = Instant.parse("2026-09-01T01:00:00Z"),
            end = Instant.parse("2026-09-01T02:00:00Z"),
        )
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
