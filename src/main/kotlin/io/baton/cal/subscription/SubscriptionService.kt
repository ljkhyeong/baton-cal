package io.baton.cal.subscription

import io.baton.cal.config.CalProperties
import io.baton.cal.persistence.CalendarSubscriptionRepository
import io.baton.cal.persistence.CalendarSubscriptionRow
import io.baton.cal.persistence.CalendarSubscriptionStatus
import io.baton.cal.persistence.SeasonFeedHeaders
import io.baton.cal.persistence.SeasonFeedProjectionRow
import io.baton.cal.projection.SeasonProjectionService
import io.baton.cal.web.ApiException
import io.baton.cal.web.SubscriptionActivityResponse
import io.baton.cal.web.SubscriptionCredential
import io.baton.cal.web.SubscriptionStatusResponse
import io.baton.cal.web.conflict
import io.baton.cal.web.resourceNotFound
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@Service
class SubscriptionService(
    private val repository: CalendarSubscriptionRepository,
    private val projectionService: SeasonProjectionService,
    private val tokenCodec: SubscriptionTokenCodec,
    private val properties: CalProperties,
    private val clock: Clock,
    meterRegistry: MeterRegistry,
) {
    private val fetchRecordFailures: Counter = Counter.builder("baton.cal.subscription.fetch.record.failures")
        .description("구독 최근 조회일을 기록하지 못한 공개 피드 조회")
        .register(meterRegistry)

    fun getStatus(subscriptionId: UUID): SubscriptionStatusResponse {
        val subscription = findSubscription(subscriptionId)
        return SubscriptionStatusResponse(
            subscriptionId = subscription.id,
            seasonId = subscription.seasonId,
            status = subscription.status,
            generationMatches = subscription.generationMatches(),
        )
    }

    /** 이전 구독 세대의 날짜는 백업에서 복원된 기록이므로 현재 세대 자격 증명의 최근 조회일만 보고한다. */
    fun getActivity(subscriptionId: UUID): SubscriptionActivityResponse {
        val subscription = findSubscription(subscriptionId)
        return SubscriptionActivityResponse(
            subscriptionId = subscription.id,
            lastFetchedOn = subscription.lastFetchedOn.takeIf { subscription.generationMatches() },
        )
    }

    private fun findSubscription(subscriptionId: UUID): CalendarSubscriptionRow =
        repository.findById(subscriptionId) ?: throw resourceNotFound("구독을 찾을 수 없습니다")

    private fun CalendarSubscriptionRow.generationMatches() = credentialGeneration == properties.subscriptionGeneration

    @Transactional
    fun create(seasonId: UUID, subscriptionId: UUID = UUID.randomUUID()): SubscriptionCredential {
        ensureCredentialIssuanceAllowed()
        repository.findById(subscriptionId)?.let { rejectExistingSubscription(it, seasonId) }
        projectionService.ensureProjection(seasonId)
        val token = tokenCodec.generate()
        val inserted = repository.insert(
            CalendarSubscriptionRow(
                id = subscriptionId,
                seasonId = seasonId,
                tokenHash = tokenCodec.hash(token),
                credentialGeneration = properties.subscriptionGeneration,
                status = CalendarSubscriptionStatus.ACTIVE,
            ),
        )
        if (!inserted) {
            rejectExistingSubscription(requireNotNull(repository.findById(subscriptionId)), seasonId)
        }
        return SubscriptionCredential(subscriptionId, token, feedUri(token))
    }

    private fun rejectExistingSubscription(existing: CalendarSubscriptionRow, seasonId: UUID): Nothing {
        if (existing.seasonId != seasonId) {
            throw conflict(
                code = "SUBSCRIPTION_SCOPE_CONFLICT",
                message = "같은 구독 ID를 다른 시즌에 사용할 수 없습니다",
            )
        }
        throw conflict(
            code = "SUBSCRIPTION_ALREADY_EXISTS",
            message = "이미 생성된 구독입니다. 상태를 조회한 뒤 필요한 경우 토큰을 다시 발급하세요",
        )
    }

    @Transactional
    fun rotate(subscriptionId: UUID): SubscriptionCredential {
        ensureCredentialIssuanceAllowed()
        val current = repository.findById(subscriptionId)
            ?.takeIf { it.status == CalendarSubscriptionStatus.ACTIVE }
            ?: throw resourceNotFound("active subscription was not found")

        val token = tokenCodec.generate()
        val replacementHash = tokenCodec.hash(token)

        if (!repository.rotate(subscriptionId, current.tokenHash, replacementHash, properties.subscriptionGeneration)) {
            throw concurrentChange()
        }
        return SubscriptionCredential(subscriptionId, token, feedUri(token))
    }

    @Transactional
    fun revoke(subscriptionId: UUID) {
        val current = repository.findById(subscriptionId)
            ?: throw resourceNotFound("subscription was not found")
        if (current.status == CalendarSubscriptionStatus.REVOKED) return
        if (!repository.revoke(subscriptionId, current.tokenHash)) throw concurrentChange()
    }

    fun findFeed(token: String): SeasonFeedProjectionRow? {
        val tokenHash = tokenCodec.hash(token)
        return repository.findProjectionByActiveTokenHash(tokenHash, properties.subscriptionGeneration)
            ?.also { recordFetch(tokenHash) }
    }

    fun findFeedHeaders(token: String): SeasonFeedHeaders? {
        val tokenHash = tokenCodec.hash(token)
        return repository.findProjectionHeadersByActiveTokenHash(tokenHash, properties.subscriptionGeneration)
            ?.also { recordFetch(tokenHash) }
    }

    // 최근 조회일은 진단 값이므로 피드 조회와 다른 문장으로 기록하고, 기록하지 못해도 피드는 그대로 응답한다.
    private fun recordFetch(tokenHash: String) {
        try {
            repository.recordFetch(
                tokenHash,
                properties.subscriptionGeneration,
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC),
            )
        } catch (exception: DataAccessException) {
            fetchRecordFailures.increment()
        }
    }

    private fun concurrentChange() = conflict(
        code = "SUBSCRIPTION_CONFLICT",
        message = "subscription was changed concurrently",
    )

    private fun ensureCredentialIssuanceAllowed() {
        if (properties.recoveryMode) {
            throw ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RECOVERY_IN_PROGRESS",
                "subscription issuance is disabled during recovery",
            )
        }
    }

    private fun feedUri(token: String): URI = UriComponentsBuilder
        .fromUri(properties.publicBaseUrl)
        .pathSegment("calendars", "v1", "$token.ics")
        .build()
        .toUri()
}
