package io.baton.cal.support

import io.baton.cal.contract.andReturnValid
import io.baton.cal.contract.contractExample
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// [InternalHttpTest]의 속성 값이다. 다른 테스트도 요청 헤더와 기대값에 같은 값을 쓴다.
const val TEST_INTERNAL_TOKEN = "test-internal-token-that-is-long-enough"
const val TEST_PREVIOUS_INTERNAL_TOKEN = "test-previous-internal-token-that-is-long-enough"
const val TEST_PUBLIC_BASE_URL = "https://calendar.example.test"
const val INTERNAL_BEARER_CHALLENGE = "Bearer realm=\"baton-cal-internal\""

const val SNAPSHOT_PATH = "/internal/api/v1/schedule-snapshots"
const val SEASON_CALENDAR_METADATA_PATH = "/internal/api/v1/seasons/{seasonId}/calendar-metadata"

fun MockHttpServletRequestBuilder.bearer(): MockHttpServletRequestBuilder =
    header(HttpHeaders.AUTHORIZATION, "Bearer $TEST_INTERNAL_TOKEN")

fun MockHttpServletRequestBuilder.jsonContent(body: String): MockHttpServletRequestBuilder =
    contentType(MediaType.APPLICATION_JSON).content(body)

fun authorizedGet(path: String, vararg uriVariables: Any) = get(path, *uriVariables).bearer()

fun authorizedPost(path: String, vararg uriVariables: Any) = post(path, *uriVariables).bearer()

fun authorizedPut(path: String, vararg uriVariables: Any) = put(path, *uriVariables).bearer()

fun authorizedDelete(path: String, vararg uriVariables: Any) = delete(path, *uriVariables).bearer()

/** 구독을 만들고 계약 스키마를 통과한 자격 증명 JSON을 반환한다. */
fun MockMvc.createSubscription(seasonId: String): String = perform(
    authorizedPost("/internal/api/v1/subscriptions").jsonContent("""{"seasonId":"$seasonId"}"""),
)
    .andExpect(status().isCreated)
    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
    .andReturnValid("subscription-credential.v1.schema.json", "구독 생성 응답")

/** 일정 스냅샷을 보낸다. 오류 응답은 호출자가 확인한다. */
fun MockMvc.postSnapshot(payload: String): ResultActions = perform(authorizedPost(SNAPSHOT_PATH).jsonContent(payload))

/** 일정 스냅샷을 보내고 수신 판정과 응답 스키마를 확인한다. */
fun MockMvc.ingestSnapshot(payload: String, expectedResult: String = "APPLIED"): String = postSnapshot(payload)
    .andExpect(status().isOk)
    .andExpect(jsonPath("$.result").value(expectedResult))
    .andReturnValid("schedule-snapshot-result.v1.schema.json", "일정 스냅샷 $expectedResult 응답")

/** `contracts/examples`의 일정 스냅샷 예시를 보내고 반영됐는지 확인한다. */
fun MockMvc.ingestSnapshotExample(fileName: String): String = ingestSnapshot(contractExample(fileName))

fun seasonCalendarMetadataRequest(seasonId: Any, payload: String): MockHttpServletRequestBuilder =
    authorizedPut(SEASON_CALENDAR_METADATA_PATH, seasonId).jsonContent(payload)

/** 시즌 표시 이름을 보내고 계약 스키마를 통과한 응답 JSON을 반환한다. */
fun MockMvc.updateSeasonCalendarMetadata(seasonId: Any, payload: String): String =
    perform(seasonCalendarMetadataRequest(seasonId, payload))
        .andExpect(status().isOk)
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.seasonId").value(seasonId.toString()))
        .andReturnValid("season-calendar-metadata-result.v1.schema.json", "시즌 이름 수신 응답")
