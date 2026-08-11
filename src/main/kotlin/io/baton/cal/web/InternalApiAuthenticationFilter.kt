package io.baton.cal.web

import io.baton.cal.config.CalProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Component
class InternalApiAuthenticationFilter(
    properties: CalProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val expectedToken = properties.internalToken.toByteArray(StandardCharsets.UTF_8)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val requestPath = request.requestURI.removePrefix(request.contextPath)
        return !request.servletPath.startsWith(INTERNAL_PATH_PREFIX) &&
            !requestPath.startsWith(INTERNAL_PATH_PREFIX)
    }

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

        if (presented == null || !MessageDigest.isEqual(expectedToken, presented)) {
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

    private companion object {
        const val INTERNAL_PATH_PREFIX = "/internal"
        const val BEARER_PREFIX = "Bearer "
    }
}
