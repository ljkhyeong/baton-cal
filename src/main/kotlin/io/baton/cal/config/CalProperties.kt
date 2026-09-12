package io.baton.cal.config

import java.net.InetAddress
import java.net.URI
import java.util.UUID
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("baton.cal")
class CalProperties(
    val internalToken: String,
    val publicBaseUrl: URI = URI.create("http://localhost:8080"),
    val previousInternalToken: String? = null,
    subscriptionGeneration: String = DEFAULT_SUBSCRIPTION_GENERATION.toString(),
    val recoveryMode: Boolean = false,
) {
    val subscriptionGeneration: UUID = StandardUuid.parse(subscriptionGeneration)

    init {
        require(internalToken.isValidInternalToken()) {
            "internalToken은 RFC 6750 Bearer 형식으로 32자 이상이어야 한다"
        }
        previousInternalToken?.let {
            require(it.isValidInternalToken()) {
                "previousInternalToken은 RFC 6750 Bearer 형식으로 32자 이상이어야 한다"
            }
            require(it != internalToken) {
                "previousInternalToken은 internalToken과 달라야 한다"
            }
        }
        val publicHost = requireNotNull(publicBaseUrl.host) {
            "publicBaseUrl에는 호스트를 포함한 전체 URL을 지정해야 한다"
        }
        require(
            publicBaseUrl.scheme.equals("https", ignoreCase = true) ||
                publicBaseUrl.scheme.equals("http", ignoreCase = true) && publicHost.isLoopbackHost(),
        ) { "publicBaseUrl은 HTTPS 또는 로컬 개발용 HTTP를 사용해야 한다" }
        require(publicBaseUrl.query == null && publicBaseUrl.fragment == null) {
            "publicBaseUrl에는 쿼리나 프래그먼트를 넣을 수 없다"
        }
        require(publicBaseUrl.userInfo == null) { "publicBaseUrl에는 사용자 이름이나 비밀번호를 넣을 수 없다" }
        require(this.subscriptionGeneration != NIL_UUID) {
            "subscriptionGeneration은 모든 자릿수가 0인 UUID일 수 없다"
        }
    }

    companion object {
        val DEFAULT_SUBSCRIPTION_GENERATION: UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000001")

        private val NIL_UUID: UUID = UUID(0, 0)
    }
}

private fun String.isValidInternalToken(): Boolean =
    length >= 32 && matches(INTERNAL_TOKEN_PATTERN)

private val INTERNAL_TOKEN_PATTERN = Regex("[A-Za-z0-9._~+/\\-]+=*")

private fun String.isLoopbackHost(): Boolean {
    if (equals("localhost", ignoreCase = true)) return true

    return try {
        InetAddress.ofLiteral(this).isLoopbackAddress
    } catch (_: IllegalArgumentException) {
        false
    }
}
