package io.baton.cal.subscription

import io.baton.cal.calendar.IcsCalendarRenderer
import io.baton.cal.persistence.CalendarSubscriptionRepository
import io.baton.cal.persistence.CalendarSubscriptionRow
import io.baton.cal.persistence.CalendarSubscriptionStatus
import io.baton.cal.persistence.SeasonFeedProjectionRepository
import io.baton.cal.support.PostgreSqlTestContainer
import io.baton.cal.support.anyArg
import io.baton.cal.support.runConcurrently
import io.baton.cal.web.InternalResourceNotFoundException
import io.baton.cal.web.ConflictException
import io.baton.cal.web.SubscriptionCredential
import java.util.concurrent.CyclicBarrier
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.jdbc.Sql

@ImportTestcontainers(PostgreSqlTestContainer::class)
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=subscription-concurrency-test-token",
        "baton.cal.public-base-url=https://calendar.example.test",
    ],
)
@Sql("/reset-database.sql")
class SubscriptionConcurrencyTest @Autowired constructor(
    private val service: SubscriptionService,
    private val tokenCodec: SubscriptionTokenCodec,
    private val projectionRepository: SeasonFeedProjectionRepository,
) {
    @MockitoSpyBean
    private lateinit var repository: CalendarSubscriptionRepository

    @MockitoSpyBean
    private lateinit var renderer: IcsCalendarRenderer

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `같은 ID의 동시 생성은 한 자격 증명만 저장하고 다른 시즌 재사용을 거부한다`(differentSeason: Boolean) {
        val otherSeasonId = UUID.randomUUID()
        // 두 시즌의 투영을 미리 만들어 동시 생성이 구독 저장 지점에서만 경합하게 한다.
        service.create(SEASON_ID)
        service.create(otherSeasonId)
        val subscriptionId = UUID.randomUUID()
        // 두 요청이 모두 저장 지점에 도달한 뒤 함께 저장을 시도한다.
        val bothInserting = CyclicBarrier(2)
        doAnswer { invocation ->
            bothInserting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            invocation.callRealMethod()
        }.`when`(repository).insert(anyArg(CalendarSubscriptionRow::class.java, PLACEHOLDER_ROW))

        val outcomes = runConcurrently(
            { runCatching { service.create(SEASON_ID, subscriptionId) } },
            { runCatching { service.create(if (differentSeason) otherSeasonId else SEASON_ID, subscriptionId) } },
        )

        val winner = outcomes.single { it.isSuccess }.getOrThrow()
        assertThat(outcomes.single { it.isFailure }.exceptionOrNull())
            .isInstanceOfSatisfying(ConflictException::class.java) {
                assertThat(it.code).isEqualTo(
                    if (differentSeason) "SUBSCRIPTION_SCOPE_CONFLICT" else "SUBSCRIPTION_ALREADY_EXISTS",
                )
            }
        assertThat(repository.findById(subscriptionId)?.tokenHash).isEqualTo(tokenCodec.hash(winner.token))
        assertThat(service.findFeed(winner.token)).isNotNull()
    }

    @Test
    fun `다른 시즌의 ID 재사용은 캘린더 생성 실패에 영향받지 않는다`() {
        val initial = service.create(SEASON_ID)
        val otherSeasonId = UUID.randomUUID()
        doThrow(IllegalStateException("캘린더 생성 실패"))
            .`when`(renderer).render(otherSeasonId, emptyList(), null)

        assertThatThrownBy { service.create(otherSeasonId, initial.subscriptionId) }
            .isInstanceOfSatisfying(ConflictException::class.java) {
                assertThat(it.code).isEqualTo("SUBSCRIPTION_SCOPE_CONFLICT")
            }

        assertThat(service.findFeed(initial.token)).isNotNull()
        assertThat(projectionRepository.findHeadersBySeasonId(otherSeasonId)).isNull()
    }

    @Test
    fun `캘린더 생성 실패는 구독을 저장하지 않고 같은 ID 재시도를 허용한다`() {
        val subscriptionId = UUID.randomUUID()
        val failure = IllegalStateException("캘린더 생성 실패")
        doThrow(failure).`when`(renderer).render(SEASON_ID, emptyList(), null)

        assertThatThrownBy { service.create(SEASON_ID, subscriptionId) }.isSameAs(failure)
        assertThat(repository.findById(subscriptionId)).isNull()
        assertThat(projectionRepository.findHeadersBySeasonId(SEASON_ID)).isNull()

        doCallRealMethod().`when`(renderer).render(SEASON_ID, emptyList(), null)
        val credential = service.create(SEASON_ID, subscriptionId)

        assertThat(credential.subscriptionId).isEqualTo(subscriptionId)
        assertThat(service.findFeed(credential.token)).isNotNull()
    }

    @Test
    fun `concurrent rotations commit exactly one credential and report one conflict`() {
        val initial = service.create(SEASON_ID)
        synchronizeFirstTwoReads(initial.subscriptionId)

        val outcomes = runConcurrently(
            { runCatching { service.rotate(initial.subscriptionId) } },
            { runCatching { service.rotate(initial.subscriptionId) } },
        )

        val winner = outcomes.single { it.isSuccess }.getOrThrow()
        assertSubscriptionConflict(outcomes.single { it.isFailure }.exceptionOrNull())
        assertThat(repository.findById(initial.subscriptionId)?.tokenHash).isEqualTo(tokenCodec.hash(winner.token))
        assertThat(service.findFeed(initial.token)).isNull()
        assertThat(service.findFeed(winner.token)).isNotNull()
    }

    @Test
    fun `concurrent rotation and revocation allow only one observed state to commit`() {
        val initial = service.create(SEASON_ID)
        synchronizeFirstTwoReads(initial.subscriptionId)

        val (rotation, revocation) = runConcurrently<Result<SubscriptionCredential?>>(
            { runCatching { service.rotate(initial.subscriptionId) } },
            { runCatching { service.revoke(initial.subscriptionId).let { null } } },
        )

        assertSubscriptionConflict(listOf(rotation, revocation).single { it.isFailure }.exceptionOrNull())

        when (repository.findById(initial.subscriptionId)?.status) {
            CalendarSubscriptionStatus.ACTIVE ->
                assertThat(service.findFeed(checkNotNull(rotation.getOrThrow()).token)).isNotNull()

            CalendarSubscriptionStatus.REVOKED -> assertThat(revocation.isSuccess).isTrue()

            null -> throw AssertionError("subscription disappeared during the race")
        }
        assertThat(service.findFeed(initial.token)).isNull()
    }

    @Test
    fun `sequential revocation is idempotent and revoked subscription cannot rotate`() {
        val initial = service.create(SEASON_ID)

        service.revoke(initial.subscriptionId)
        val firstRevokedState = repository.findById(initial.subscriptionId)
        service.revoke(initial.subscriptionId)

        assertThat(repository.findById(initial.subscriptionId)).isEqualTo(firstRevokedState)
        assertThat(firstRevokedState?.status).isEqualTo(CalendarSubscriptionStatus.REVOKED)
        assertThat(service.findFeed(initial.token)).isNull()

        assertThatThrownBy { service.rotate(initial.subscriptionId) }
            .isInstanceOfSatisfying(InternalResourceNotFoundException::class.java) {
                assertThat(it.code).isEqualTo("RESOURCE_NOT_FOUND")
            }
    }

    // 두 트랜잭션이 같은 초기 상태를 읽은 뒤 갱신하도록 처음 두 조회를 함께 진행시킨다.
    private fun synchronizeFirstTwoReads(subscriptionId: UUID) {
        val bothRead = CyclicBarrier(2)
        val readCount = AtomicInteger()

        doAnswer { invocation ->
            val row = invocation.callRealMethod() as CalendarSubscriptionRow?
            if (readCount.incrementAndGet() <= 2) bothRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            row
        }.`when`(repository).findById(subscriptionId)
    }

    private fun assertSubscriptionConflict(error: Throwable?) {
        assertThat(error).isInstanceOfSatisfying(ConflictException::class.java) {
            assertThat(it.code).isEqualTo("SUBSCRIPTION_CONFLICT")
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        val PLACEHOLDER_ROW =
            CalendarSubscriptionRow(UUID(0, 0), UUID(0, 0), "", UUID(0, 0), CalendarSubscriptionStatus.ACTIVE)
        val SEASON_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    }
}
