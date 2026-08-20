package io.baton.cal.web

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
