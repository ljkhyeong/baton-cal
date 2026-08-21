package io.baton.cal.subscription

import kotlin.io.encoding.Base64
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom

@Component
class SubscriptionTokenCodec(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun generate(): String {
        val entropy = ByteArray(TOKEN_ENTROPY_BYTES)
        secureRandom.nextBytes(entropy)
        return TOKEN_ENCODER.encode(entropy)
    }

    fun hash(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.US_ASCII))
        .toHexString()

    private companion object {
        const val TOKEN_ENTROPY_BYTES = 32
        val TOKEN_ENCODER: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }
}
