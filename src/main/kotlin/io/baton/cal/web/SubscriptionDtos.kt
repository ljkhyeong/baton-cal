package io.baton.cal.web

import io.baton.cal.persistence.CalendarSubscriptionStatus
import java.net.URI
import java.util.UUID

data class CreateSubscriptionRequest(
    val seasonId: UUID,
)

class SubscriptionCredential(
    val subscriptionId: UUID,
    val token: String,
    val feedUrl: URI,
)

data class SubscriptionStatusResponse(
    val subscriptionId: UUID,
    val seasonId: UUID,
    val status: CalendarSubscriptionStatus,
    val generationMatches: Boolean,
)
