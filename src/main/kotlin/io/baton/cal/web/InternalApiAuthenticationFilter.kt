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
    private val expectedTokens = buildList {
        add(ExpectedToken(AuthenticationResult.CURRENT, properties.internalToken.encodeToByteArray()))
        properties.previousInternalToken?.let {
            add(ExpectedToken(AuthenticationResult.PREVIOUS, it.encodeToByteArray()))
        }
    }
    private val authenticationCounters: Map<AuthenticationResult, Counter> =
        AuthenticationResult.entries.associateWith { result ->
            Counter.builder(AUTHENTICATION_METRIC)
                .description("내부 API 인증 결과")
                .tag("result", result.tagValue)
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

    private fun matchingAuthenticationResult(presented: ByteArray): AuthenticationResult? {
        var matched: AuthenticationResult? = null
        expectedTokens.forEach { expected ->
            if (MessageDigest.isEqual(expected.value, presented)) {
                matched = expected.result
            }
        }
        return matched
    }

    private data class ExpectedToken(
        val result: AuthenticationResult,
        val value: ByteArray,
    )

    private enum class AuthenticationResult(val tagValue: String) {
        CURRENT("current"),
        PREVIOUS("previous"),
        UNAUTHORIZED("unauthorized"),
    }

    private companion object {
        const val BEARER_SCHEME = "Bearer"
        const val BEARER_CHALLENGE = "Bearer realm=\"baton-cal-internal\""
        const val AUTHENTICATION_METRIC = "baton.cal.internal.authentication"
    }
}
