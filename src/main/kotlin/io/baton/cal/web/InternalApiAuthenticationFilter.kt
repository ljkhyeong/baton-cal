package io.baton.cal.web

import io.baton.cal.config.CalProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Component
@FilterRegistration(urlPatterns = ["/internal/*"])
class InternalApiAuthenticationFilter(
    properties: CalProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val expectedTokens = listOfNotNull(
        properties.internalToken,
        properties.previousInternalToken,
    ).map { it.toByteArray(StandardCharsets.UTF_8) }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authorization = request.getHeader(HttpHeaders.AUTHORIZATION)
        val presented = authorization
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.substring(BEARER_PREFIX.length)
            ?.toByteArray(StandardCharsets.UTF_8)

        if (presented == null || !matchesExpectedToken(presented)) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_JSON_VALUE
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

    private fun matchesExpectedToken(presented: ByteArray): Boolean {
        var matched = false
        expectedTokens.forEach { expected ->
            matched = MessageDigest.isEqual(expected, presented) or matched
        }
        return matched
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
