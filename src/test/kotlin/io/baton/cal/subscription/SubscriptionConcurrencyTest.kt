package io.baton.cal.subscription

import io.baton.cal.persistence.CalendarSubscriptionRepository
import io.baton.cal.persistence.CalendarSubscriptionRow
import io.baton.cal.persistence.CalendarSubscriptionStatus
import io.baton.cal.web.InternalResourceNotFoundException
import io.baton.cal.web.SnapshotConflictException
import io.baton.cal.web.SubscriptionCredential
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=subscription-concurrency-test-token",
        "baton.cal.public-base-url=https://calendar.example.test",
    ],
)
class SubscriptionConcurrencyTest @Autowired constructor(
    private val service: SubscriptionService,
    private val tokenCodec: SubscriptionTokenCodec,
    private val jdbcClient: JdbcClient,
) {
    @MockitoSpyBean
    private lateinit var repository: CalendarSubscriptionRepository

    @BeforeEach
    fun resetDatabase() {
        reset(repository)
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

        assertThat(outcomes.count { it !is Failed }).isEqualTo(1)
        assertSubscriptionConflict(outcomes.filterIsInstance<Failed>().single().error)

        when (repository.findById(initial.subscriptionId)?.status) {
            CalendarSubscriptionStatus.ACTIVE -> {
                val winner = outcomes.filterIsInstance<Rotated>().single()
                assertThat(outcomes).noneMatch { it is Revoked }
                assertThat(service.findFeed(initial.token)).isNull()
                assertThat(service.findFeed(winner.credential.token)).isNotNull()
            }

            CalendarSubscriptionStatus.REVOKED -> {
                assertThat(outcomes).anyMatch { it is Revoked }
                assertThat(outcomes).noneMatch { it is Rotated }
                val issuedTokens = listOf(initial.token) +
                    outcomes.filterIsInstance<Rotated>().map { it.credential.token }
                assertThat(issuedTokens).allSatisfy { token ->
                    assertThat(service.findFeed(token)).isNull()
                }
            }

            null -> throw AssertionError("subscription disappeared during the race")
        }
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

        val error = catchThrowable { service.rotate(initial.subscriptionId) }
        assertThat(error).isInstanceOf(InternalResourceNotFoundException::class.java)
        assertThat((error as InternalResourceNotFoundException).code).isEqualTo("RESOURCE_NOT_FOUND")
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

    private fun <T> runConcurrently(first: () -> T, second: () -> T): List<T> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        return try {
            val futures = listOf(first, second).map { operation ->
                executor.submit<T> {
                    ready.countDown()
                    check(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    operation()
                }
            }
            check(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun attempt(operation: () -> OperationOutcome): OperationOutcome =
        try {
            operation()
        } catch (error: Throwable) {
            Failed(error)
        }

    private fun assertSubscriptionConflict(error: Throwable) {
        assertThat(error).isInstanceOf(SnapshotConflictException::class.java)
        assertThat((error as SnapshotConflictException).code).isEqualTo("SUBSCRIPTION_CONFLICT")
    }

    private sealed interface OperationOutcome

    private data class Rotated(val credential: SubscriptionCredential) : OperationOutcome

    private data object Revoked : OperationOutcome

    private data class Failed(val error: Throwable) : OperationOutcome

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        val SEASON_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

        @Container
        @ServiceConnection
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18.4-alpine")
    }
}
