package io.baton.cal.web

import java.net.URI
import java.util.UUID

data class CreateSubscriptionRequest(
    val seasonId: UUID,
)

data class SubscriptionCredential(
    val subscriptionId: UUID,
    val token: String,
    val feedUrl: URI,
) {
    override fun toString(): String =
        "SubscriptionCredential(subscriptionId=$subscriptionId, token=[REDACTED], feedUrl=[REDACTED])"
}
