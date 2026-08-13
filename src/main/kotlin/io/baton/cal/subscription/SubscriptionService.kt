package io.baton.cal.subscription

import io.baton.cal.config.CalProperties
import io.baton.cal.persistence.CalendarSubscriptionRepository
import io.baton.cal.persistence.CalendarSubscriptionRow
import io.baton.cal.persistence.CalendarSubscriptionStatus
import io.baton.cal.persistence.SeasonFeedProjectionRow
import io.baton.cal.projection.SeasonProjectionService
import io.baton.cal.web.InternalResourceNotFoundException
import io.baton.cal.web.SnapshotConflictException
import io.baton.cal.web.SubscriptionCredential
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
    @Transactional
    fun create(seasonId: UUID): SubscriptionCredential {
        projectionService.ensureProjection(seasonId)
        val token = tokenCodec.generate()
        val subscriptionId = UUID.randomUUID()
        repository.insert(
            CalendarSubscriptionRow(
                id = subscriptionId,
                seasonId = seasonId,
                tokenHash = tokenCodec.hash(token),
                status = CalendarSubscriptionStatus.ACTIVE,
            ),
        )
        return SubscriptionCredential(subscriptionId, token, feedUri(token))
    }

    @Transactional
    fun rotate(subscriptionId: UUID): SubscriptionCredential {
        val current = repository.findById(subscriptionId)
            ?.takeIf { it.status == CalendarSubscriptionStatus.ACTIVE }
            ?: throw InternalResourceNotFoundException("active subscription was not found")

        val token = tokenCodec.generate()
        val replacementHash = tokenCodec.hash(token)

        if (repository.rotate(subscriptionId, current.tokenHash, replacementHash)) {
            return SubscriptionCredential(subscriptionId, token, feedUri(token))
        }

        if (repository.findById(subscriptionId) == null) {
            throw InternalResourceNotFoundException("active subscription was not found")
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
            val latest = repository.findById(subscriptionId)
                ?: throw InternalResourceNotFoundException("subscription was not found")
            if (latest.status == CalendarSubscriptionStatus.REVOKED &&
                latest.tokenHash == current.tokenHash
            ) {
                return
            }
            throw SnapshotConflictException(
                code = "SUBSCRIPTION_CONFLICT",
                message = "subscription was changed concurrently",
            )
        }
    }

    @Transactional(readOnly = true)
    fun findFeed(token: String): SeasonFeedProjectionRow? =
        repository.findProjectionByActiveTokenHash(tokenCodec.hash(token))

    private fun feedUri(token: String): URI = UriComponentsBuilder
        .fromUri(properties.publicBaseUrl)
        .pathSegment("calendars", "v1", "$token.ics")
        .build()
        .toUri()
}
