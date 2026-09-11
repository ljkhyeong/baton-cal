package io.baton.cal.subscription

import io.baton.cal.calendar.IcsCalendarRenderer
import io.baton.cal.persistence.CalendarSubscriptionRepository
import io.baton.cal.persistence.CalendarSubscriptionRow
import io.baton.cal.persistence.CalendarSubscriptionStatus
import io.baton.cal.persistence.SeasonFeedProjectionRepository
import io.baton.cal.support.PostgreSqlTestContainer
import io.baton.cal.web.InternalResourceNotFoundException
import io.baton.cal.web.SnapshotConflictException
import io.baton.cal.web.SubscriptionCredential
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
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
        val seed = service.create(SEASON_ID)
        service.create(otherSeasonId)
        val matcherPlaceholder = requireNotNull(repository.findById(seed.subscriptionId))
        val subscriptionId = UUID.randomUUID()
        val bothInserting = CountDownLatch(2)
        doAnswer { invocation ->
            bothInserting.countDown()
            check(bothInserting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "두 구독 생성 요청이 저장 지점에 도달하지 못했습니다"
            }
            invocation.callRealMethod()
        }.`when`(repository).insert(any(CalendarSubscriptionRow::class.java) ?: matcherPlaceholder)

        val outcomes = runConcurrently(
            { runCatching { service.create(SEASON_ID, subscriptionId) } },
            { runCatching { service.create(if (differentSeason) otherSeasonId else SEASON_ID, subscriptionId) } },
        )

        val winner = outcomes.single { it.isSuccess }.getOrThrow()
        assertThat(outcomes.single { it.isFailure }.exceptionOrNull())
            .isInstanceOfSatisfying(SnapshotConflictException::class.java) {
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
            .isInstanceOfSatisfying(SnapshotConflictException::class.java) {
                assertThat(it.code).isEqualTo("SUBSCRIPTION_SCOPE_CONFLICT")
            }

        assertThat(service.findFeed(initial.token)).isNotNull()
        assertThat(projectionRepository.findMetadataBySeasonId(otherSeasonId)).isNull()
    }

    @Test
    fun `캘린더 생성 실패는 구독을 저장하지 않고 같은 ID 재시도를 허용한다`() {
        val subscriptionId = UUID.randomUUID()
        val failure = IllegalStateException("캘린더 생성 실패")
        doThrow(failure).`when`(renderer).render(SEASON_ID, emptyList(), null)

        assertThatThrownBy { service.create(SEASON_ID, subscriptionId) }.isSameAs(failure)
        assertThat(repository.findById(subscriptionId)).isNull()
        assertThat(projectionRepository.findMetadataBySeasonId(SEASON_ID)).isNull()

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
            { attempt { Rotated(service.rotate(initial.subscriptionId)) } },
            { attempt { Rotated(service.rotate(initial.subscriptionId)) } },
        )

        val winner = outcomes.filterIsInstance<Rotated>().single()
        assertSubscriptionConflict(outcomes.filterIsInstance<Failed>().single().error)
        assertThat(repository.findById(initial.subscriptionId)?.tokenHash)
            .isEqualTo(tokenCodec.hash(winner.credential.token))
        assertThat(service.findFeed(initial.token)).isNull()
        assertThat(service.findFeed(winner.credential.token)).isNotNull()
    }

    @Test
    fun `concurrent rotation and revocation allow only one observed state to commit`() {
        val initial = service.create(SEASON_ID)
        synchronizeFirstTwoReads(initial.subscriptionId)

        val outcomes = runConcurrently(
            { attempt { Rotated(service.rotate(initial.subscriptionId)) } },
            {
                attempt {
                    service.revoke(initial.subscriptionId)
                    Revoked
                }
            },
        )

        assertSubscriptionConflict(outcomes.filterIsInstance<Failed>().single().error)

        when (repository.findById(initial.subscriptionId)?.status) {
            CalendarSubscriptionStatus.ACTIVE -> {
                val winner = outcomes.filterIsInstance<Rotated>().single()
                assertThat(service.findFeed(winner.credential.token)).isNotNull()
            }

            CalendarSubscriptionStatus.REVOKED -> {
                assertThat(outcomes).anyMatch { it is Revoked }
            }

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

    private fun synchronizeFirstTwoReads(subscriptionId: UUID) {
        val bothRead = CountDownLatch(2)
        val readCount = AtomicInteger()

        doAnswer { invocation ->
            val row = invocation.callRealMethod() as CalendarSubscriptionRow?
            if (readCount.incrementAndGet() <= 2) {
                bothRead.countDown()
                check(bothRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "both subscription transactions did not read the same initial state"
                }
            }
            row
        }.`when`(repository).findById(subscriptionId)
    }

    private fun <T> runConcurrently(first: () -> T, second: () -> T): List<T> =
        Executors.newFixedThreadPool(2).use { executor ->
            executor.invokeAll(
                listOf(Callable(first), Callable(second)),
                TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            ).map { it.get() }
        }

    private fun attempt(operation: () -> OperationOutcome): OperationOutcome =
        runCatching(operation).fold(onSuccess = { it }, onFailure = ::Failed)

    private fun assertSubscriptionConflict(error: Throwable) {
        assertThat(error).isInstanceOfSatisfying(SnapshotConflictException::class.java) {
            assertThat(it.code).isEqualTo("SUBSCRIPTION_CONFLICT")
        }
    }

    private sealed interface OperationOutcome

    private data class Rotated(val credential: SubscriptionCredential) : OperationOutcome

    private data object Revoked : OperationOutcome

    private data class Failed(val error: Throwable) : OperationOutcome

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        val SEASON_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    }
}
