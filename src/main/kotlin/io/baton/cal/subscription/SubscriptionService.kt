package io.baton.cal.subscription

import io.baton.cal.config.CalProperties
import io.baton.cal.persistence.CalendarSubscriptionRepository
import io.baton.cal.persistence.CalendarSubscriptionRow
import io.baton.cal.persistence.CalendarSubscriptionStatus
import io.baton.cal.persistence.SeasonFeedProjectionMetadata
import io.baton.cal.persistence.SeasonFeedProjectionRow
import io.baton.cal.projection.SeasonProjectionService
import io.baton.cal.web.InternalResourceNotFoundException
import io.baton.cal.web.RecoveryInProgressException
import io.baton.cal.web.SnapshotConflictException
import io.baton.cal.web.SubscriptionCredential
import io.baton.cal.web.SubscriptionStatusResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.util.UUID

@Service
class SubscriptionService(
    private val repository: CalendarSubscriptionRepository,
    private val projectionService: SeasonProjectionService,
    private val tokenCodec: SubscriptionTokenCodec,
    private val properties: CalProperties,
) {
    @Transactional(readOnly = true)
    fun getStatus(subscriptionId: UUID): SubscriptionStatusResponse {
        val subscription = repository.findById(subscriptionId)
            ?: throw InternalResourceNotFoundException("구독을 찾을 수 없습니다")
        return SubscriptionStatusResponse(
            subscriptionId = subscription.id,
            seasonId = subscription.seasonId,
            status = subscription.status,
            generationMatches = subscription.credentialGeneration == properties.subscriptionGeneration,
        )
    }

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
            throw SnapshotConflictException(
                code = "SUBSCRIPTION_SCOPE_CONFLICT",
                message = "같은 구독 ID를 다른 시즌에 사용할 수 없습니다",
            )
        }
        throw SnapshotConflictException(
            code = "SUBSCRIPTION_ALREADY_EXISTS",
            message = "이미 생성된 구독입니다. 상태를 조회한 뒤 필요한 경우 토큰을 다시 발급하세요",
        )
    }

    @Transactional
    fun rotate(subscriptionId: UUID): SubscriptionCredential {
        ensureCredentialIssuanceAllowed()
        val current = repository.findById(subscriptionId)
            ?.takeIf { it.status == CalendarSubscriptionStatus.ACTIVE }
            ?: throw InternalResourceNotFoundException("active subscription was not found")

        val token = tokenCodec.generate()
        val replacementHash = tokenCodec.hash(token)

        if (
            repository.rotate(
                subscriptionId,
                current.tokenHash,
                replacementHash,
                properties.subscriptionGeneration,
            )
        ) {
            return SubscriptionCredential(subscriptionId, token, feedUri(token))
        }

        throw SnapshotConflictException(
            code = "SUBSCRIPTION_CONFLICT",
            message = "subscription was changed concurrently",
        )
    }

    @Transactional
    fun revoke(subscriptionId: UUID) {
        val current = repository.findById(subscriptionId)
            ?: throw InternalResourceNotFoundException("subscription was not found")
        if (current.status == CalendarSubscriptionStatus.REVOKED) return
        if (!repository.revoke(subscriptionId, current.tokenHash)) {
            throw SnapshotConflictException(
                code = "SUBSCRIPTION_CONFLICT",
                message = "subscription was changed concurrently",
            )
        }
    }

    @Transactional(readOnly = true)
    fun findFeed(token: String): SeasonFeedProjectionRow? =
        repository.findProjectionByActiveTokenHash(
            tokenCodec.hash(token),
            properties.subscriptionGeneration,
        )

    @Transactional(readOnly = true)
    fun findFeedMetadata(token: String): SeasonFeedProjectionMetadata? =
        repository.findProjectionMetadataByActiveTokenHash(
            tokenCodec.hash(token),
            properties.subscriptionGeneration,
        )

    private fun ensureCredentialIssuanceAllowed() {
        if (properties.recoveryMode) throw RecoveryInProgressException()
    }

    private fun feedUri(token: String): URI = UriComponentsBuilder
        .fromUri(properties.publicBaseUrl)
        .pathSegment("calendars", "v1", "$token.ics")
        .build()
        .toUri()
}
