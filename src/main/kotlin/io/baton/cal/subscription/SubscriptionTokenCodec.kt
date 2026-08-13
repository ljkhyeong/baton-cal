package io.baton.cal.subscription

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

@Component
class SubscriptionTokenCodec(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun generate(): String {
        val entropy = ByteArray(TOKEN_ENTROPY_BYTES)
        secureRandom.nextBytes(entropy)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
    }

    fun hash(token: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(StandardCharsets.US_ASCII)),
    )

    private companion object {
        const val TOKEN_ENTROPY_BYTES = 32
    }
}
