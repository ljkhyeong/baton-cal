package io.baton.cal.web

import io.baton.cal.config.CalProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.web.servlet.FilterRegistration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

@Component
@FilterRegistration(urlPatterns = ["/internal/*"])
class InternalApiAuthenticationFilter(
    properties: CalProperties,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry,
) : OncePerRequestFilter() {
    private val expectedTokens = buildMap {
        put(AuthenticationResult.CURRENT, properties.internalToken.encodeToByteArray())
        properties.previousInternalToken?.let { put(AuthenticationResult.PREVIOUS, it.encodeToByteArray()) }
    }
    private val authenticationCounters: Map<AuthenticationResult, Counter> =
        AuthenticationResult.entries.associateWith { result ->
            Counter.builder(AUTHENTICATION_METRIC)
                .description("내부 API 인증 결과")
                .tag("result", result.name.lowercase())
                .register(meterRegistry)
        }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val presented = request.getHeader(HttpHeaders.AUTHORIZATION)
            ?.bearerCredential()
            ?.encodeToByteArray()

        val authenticationResult = presented?.let(::matchingAuthenticationResult)
            ?: AuthenticationResult.UNAUTHORIZED
        authenticationCounters.getValue(authenticationResult).increment()

        if (authenticationResult == AuthenticationResult.UNAUTHORIZED) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE)
            objectMapper.writeValue(
                response.outputStream,
                ApiErrorResponse(
                    code = "UNAUTHORIZED",
                    message = "valid internal bearer credential is required",
                ),
            )
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun String.bearerCredential(): String? {
        val scheme = substringBefore(' ')
        val credential = substringAfter(' ', missingDelimiterValue = "").trimStart(' ')
        return credential.takeIf {
            scheme.equals(BEARER_SCHEME, ignoreCase = true) && it.isNotEmpty()
        }
    }

    // 비교 시간이 일치한 토큰에 따라 달라지지 않도록 모든 토큰을 비교한 뒤 결과를 고른다.
    private fun matchingAuthenticationResult(presented: ByteArray): AuthenticationResult? =
        expectedTokens.filterValues { MessageDigest.isEqual(it, presented) }.keys.firstOrNull()

    private enum class AuthenticationResult {
        CURRENT,
        PREVIOUS,
        UNAUTHORIZED,
    }

    private companion object {
        const val BEARER_SCHEME = "Bearer"
        const val BEARER_CHALLENGE = "Bearer realm=\"baton-cal-internal\""
        const val AUTHENTICATION_METRIC = "baton.cal.internal.authentication"
    }
}
