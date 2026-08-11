package io.baton.cal.subscription

import org.apache.commons.codec.digest.DigestUtils
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

@Component
class SubscriptionTokenCodec(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun generate(): String {
        val entropy = ByteArray(TOKEN_ENTROPY_BYTES)
        secureRandom.nextBytes(entropy)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
    }

    fun hash(token: String): String = DigestUtils.sha256Hex(token)

    private companion object {
        const val TOKEN_ENTROPY_BYTES = 32
    }
}
