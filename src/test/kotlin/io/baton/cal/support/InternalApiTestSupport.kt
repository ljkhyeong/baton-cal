package io.baton.cal.support

import io.baton.cal.contract.andReturnValid
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// [InternalHttpTest]의 속성 값이다. 다른 테스트도 요청 헤더와 기대값에 같은 값을 쓴다.
const val TEST_INTERNAL_TOKEN = "test-internal-token-that-is-long-enough"
const val TEST_PREVIOUS_INTERNAL_TOKEN = "test-previous-internal-token-that-is-long-enough"
const val TEST_PUBLIC_BASE_URL = "https://calendar.example.test"
const val INTERNAL_BEARER_CHALLENGE = "Bearer realm=\"baton-cal-internal\""

fun MockHttpServletRequestBuilder.bearer(): MockHttpServletRequestBuilder =
    header(HttpHeaders.AUTHORIZATION, "Bearer $TEST_INTERNAL_TOKEN")

fun authorizedGet(path: String, vararg uriVariables: Any) = get(path, *uriVariables).bearer()

fun authorizedPost(path: String, vararg uriVariables: Any) = post(path, *uriVariables).bearer()

/** 구독을 만들고 계약 스키마를 통과한 자격 증명 JSON을 반환한다. */
fun MockMvc.createSubscription(seasonId: String): String = perform(
    authorizedPost("/internal/api/v1/subscriptions")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"seasonId":"$seasonId"}"""),
)
    .andExpect(status().isCreated)
    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
    .andReturnValid("subscription-credential.v1.schema.json", "구독 생성 응답")
