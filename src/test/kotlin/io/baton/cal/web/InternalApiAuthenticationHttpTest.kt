package io.baton.cal.web

import io.baton.cal.support.INTERNAL_BEARER_CHALLENGE
import io.baton.cal.support.CalIntegrationTest
import io.baton.cal.support.TEST_INTERNAL_TOKEN
import io.baton.cal.support.TEST_PREVIOUS_INTERNAL_TOKEN
import io.baton.cal.support.jsonContent
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// 내부 API Bearer 필터의 경로·자격 증명 판정.
@CalIntegrationTest
class InternalApiAuthenticationHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val meterRegistry: MeterRegistry,
) {
    @Test
    fun `행렬 매개변수 형태의 내부 경로도 인증을 요구한다`() {
        listOf(
            "/internal/api/v1;ignored/subscriptions",
            "/internal;ignored/api/v1/subscriptions",
        ).forEach { path ->
            mockMvc.perform(post(path).jsonContent("""{"seasonId":"$SEASON_ID"}"""))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        }
    }

    @Test
    fun `내부 베어러 회전 창에서는 현재 값과 이전 값만 허용한다`() {
        val currentBefore = authenticationCount("current")
        val previousBefore = authenticationCount("previous")
        val unauthorizedBefore = authenticationCount("unauthorized")

        listOf(
            "bearer $TEST_INTERNAL_TOKEN",
            "BEARER   $TEST_PREVIOUS_INTERNAL_TOKEN",
        ).forEach { authorization ->
            mockMvc.perform(
                post("/internal/api/v1/projections/seasons/{seasonId}/rebuild", SEASON_ID)
                    .header(HttpHeaders.AUTHORIZATION, authorization),
            )
                .andExpect(status().isOk)
        }

        listOf(
            "Basic $TEST_INTERNAL_TOKEN",
            "Bearer unregistered-internal-token-that-is-long-enough",
        ).forEach { authorization ->
            mockMvc.perform(
                post("/internal/api/v1/projections/seasons/{seasonId}/rebuild", SEASON_ID)
                    .header(HttpHeaders.AUTHORIZATION, authorization),
            )
                .andExpect(status().isUnauthorized)
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, INTERNAL_BEARER_CHALLENGE))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        }

        assertThat(authenticationCount("current")).isEqualTo(currentBefore + 1)
        assertThat(authenticationCount("previous")).isEqualTo(previousBefore + 1)
        assertThat(authenticationCount("unauthorized")).isEqualTo(unauthorizedBefore + 2)
    }

    private fun authenticationCount(result: String): Double =
        meterRegistry.get("baton.cal.internal.authentication").tag("result", result).counter().count()

    private companion object {
        const val SEASON_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    }
}
