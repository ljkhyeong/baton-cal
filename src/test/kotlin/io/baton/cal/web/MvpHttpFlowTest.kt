package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import io.baton.cal.calendar.events
import io.baton.cal.calendar.parseIcalendar
import io.baton.cal.calendar.requiredPropertyValue
import io.baton.cal.calendar.timeZones
import io.baton.cal.contract.ContractSchemaSupport
import io.baton.cal.support.PostgreSqlTestContainer
import io.micrometer.core.instrument.MeterRegistry
import net.fortuna.ical4j.model.Property
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.io.path.readText
import java.nio.file.Path

private const val PUBLIC_BASE_URL = "https://calendar.example.test"

@ImportTestcontainers(PostgreSqlTestContainer::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=test-internal-token-that-is-long-enough",
        "baton.cal.previous-internal-token=test-previous-internal-token-that-is-long-enough",
        "baton.cal.public-base-url=$PUBLIC_BASE_URL",
    ],
)
@Sql("/reset-database.sql")
class MvpHttpFlowTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbcClient: JdbcClient,
    private val meterRegistry: MeterRegistry,
) {

    @Test
    fun `MVP는 멱등 스냅샷과 조건부 피드 재구축 및 토큰 수명주기를 지원한다`() {
        val appliedBefore = ingestionCount("applied")
        val duplicateBefore = ingestionCount("duplicate")
        val staleBefore = ingestionCount("stale")

        mockMvc.perform(
            post("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(utcSnapshot(EVENT_1, revision = 1, summary = "Opening")),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, INTERNAL_BEARER_CHALLENGE))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))

        ingest(utcSnapshot(EVENT_1, revision = 1, summary = "Opening"), "APPLIED")
        ingest(utcSnapshot(EVENT_1, revision = 1, summary = "Opening"), "DUPLICATE")
        ingest(utcSnapshot(EVENT_2, revision = 1, summary = "Opening"), "DUPLICATE")
        assertThat(ingestionCount("applied")).isEqualTo(appliedBefore + 1)
        assertThat(ingestionCount("duplicate")).isEqualTo(duplicateBefore + 2)
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(utcSnapshot(EVENT_2, revision = 2, summary = "reused envelope")),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("EVENT_ID_CONFLICT"))
        ingest(utcSnapshot(EVENT_3, revision = 0, summary = "Old delivery"), "STALE")
        assertThat(ingestionCount("stale")).isEqualTo(staleBefore + 1)

        ingest(
            utcSnapshot(
                eventId = EVENT_4,
                revision = 2,
                summary = "Opening cancelled",
                status = "CANCELLED",
            ),
            "APPLIED",
        )
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(utcSnapshot(EVENT_5, revision = 2, summary = "conflicting revision")),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SOURCE_REVISION_CONFLICT"))

        ingest(zonedSnapshot(), "APPLIED")

        val createResult = mockMvc.perform(
            authorizedPost("/internal/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"seasonId":"$SEASON_ID"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()

        val createJson = createResult.response.contentAsString
        ContractSchemaSupport.assertValid(
            "subscription-credential.v1.schema.json",
            createJson,
            "실제 구독 생성 응답",
        )
        val subscriptionId: String = JsonPath.read(createJson, "$.subscriptionId")
        val originalToken = credentialToken(createJson)

        val firstFeed = mockMvc.perform(get("/calendars/v1/{token}.ics", originalToken))
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/calendar;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")))
            .andReturn()
        val firstCalendar = firstFeed.response.contentAsByteArray.parseIcalendar()
        val sourceEvent = firstCalendar.events().single {
            it.requiredPropertyValue(Property.UID) == "$SOURCE_ITEM_ID@cal.baton"
        }
        assertThat(sourceEvent.requiredPropertyValue(Property.SEQUENCE)).isEqualTo("2")
        assertThat(sourceEvent.requiredPropertyValue(Property.STATUS)).isEqualTo("CANCELLED")
        assertThat(firstCalendar.timeZones().map { it.timeZoneId.value })
            .contains("America/New_York")

        val originalEtag = checkNotNull(firstFeed.response.getHeader(HttpHeaders.ETAG))
        val originalLastModified = checkNotNull(firstFeed.response.getHeader(HttpHeaders.LAST_MODIFIED))

        mockMvc.perform(
            get("/calendars/v1/{token}.ics", originalToken)
                .header(HttpHeaders.IF_NONE_MATCH, "W/$originalEtag"),
        )
            .andExpect(status().isNotModified)
            .andExpect(header().string(HttpHeaders.ETAG, originalEtag))
            .andExpect(header().string(HttpHeaders.LAST_MODIFIED, originalLastModified))
            .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
            .andExpect(content().bytes(byteArrayOf()))

        mockMvc.perform(
            get("/calendars/v1/{token}.ics", originalToken)
                .header(HttpHeaders.IF_MODIFIED_SINCE, originalLastModified),
        )
            .andExpect(status().isNotModified)
            .andExpect(header().string(HttpHeaders.ETAG, originalEtag))
            .andExpect(header().string(HttpHeaders.LAST_MODIFIED, originalLastModified))
            .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
            .andExpect(content().bytes(byteArrayOf()))

        mockMvc.perform(
            get("/calendars/v1/{token}.ics", originalToken)
                .header(HttpHeaders.IF_NONE_MATCH, "\"different\"")
                .header(HttpHeaders.IF_MODIFIED_SINCE, "Wed, 31 Dec 2099 23:59:59 GMT"),
        )
            .andExpect(status().isOk)

        // 이 항목은 시즌에서 sourceUpdatedAt이 가장 크지 않더라도 표현이 바뀌면
        // 피드의 HTTP Last-Modified가 반드시 증가해야 한다.
        ingest(
            utcSnapshot(
                eventId = EVENT_7,
                revision = 3,
                summary = "Cancellation corrected",
                status = "CANCELLED",
            ),
            "APPLIED",
        )
        val refreshedFeed = mockMvc.perform(
            get("/calendars/v1/{token}.ics", originalToken)
                .header(HttpHeaders.IF_MODIFIED_SINCE, originalLastModified),
        )
            .andExpect(status().isOk)
            .andReturn()
        val refreshedBytes = refreshedFeed.response.contentAsByteArray
        val refreshedSourceEvent = refreshedBytes.parseIcalendar().events().single {
            it.requiredPropertyValue(Property.UID) == "$SOURCE_ITEM_ID@cal.baton"
        }
        assertThat(refreshedSourceEvent.requiredPropertyValue(Property.SEQUENCE)).isEqualTo("3")
        val refreshedEtag = checkNotNull(refreshedFeed.response.getHeader(HttpHeaders.ETAG))
        val refreshedLastModified = checkNotNull(refreshedFeed.response.getHeader(HttpHeaders.LAST_MODIFIED))
        assertThat(refreshedEtag).isNotEqualTo(originalEtag)
        assertThat(refreshedLastModified).isNotEqualTo(originalLastModified)

        val rebuildResult = mockMvc.perform(
            authorizedPost("/internal/api/v1/projections/seasons/{seasonId}/rebuild", SEASON_ID),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.etag").value(refreshedEtag))
            .andExpect(jsonPath("$.itemCount").value(2))
            .andReturn()
        ContractSchemaSupport.assertValid(
            "projection-rebuild-result.v1.schema.json",
            rebuildResult.response.contentAsString,
            "실제 시즌 투영 재구축 응답",
        )

        mockMvc.perform(get("/calendars/v1/{token}.ics", originalToken))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, refreshedEtag))
            .andExpect(header().string(HttpHeaders.LAST_MODIFIED, refreshedLastModified))
            .andExpect(content().bytes(refreshedBytes))

        val rotateResult = mockMvc.perform(
            authorizedPost("/internal/api/v1/subscriptions/{subscriptionId}/rotate", subscriptionId),
        )
            .andExpect(status().isOk)
            .andReturn()
        val rotateJson = rotateResult.response.contentAsString
        ContractSchemaSupport.assertValid(
            "subscription-credential.v1.schema.json",
            rotateJson,
            "실제 구독 회전 응답",
        )
        val replacementToken = credentialToken(rotateJson)
        assertThat(replacementToken).isNotEqualTo(originalToken)

        mockMvc.perform(get("/calendars/v1/{token}.ics", originalToken))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/calendars/v1/{token}.ics", replacementToken))
            .andExpect(status().isOk)
            .andExpect(content().bytes(refreshedBytes))

        mockMvc.perform(
            delete("/internal/api/v1/subscriptions/{subscriptionId}", subscriptionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $INTERNAL_TOKEN"),
        )
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/calendars/v1/{token}.ics", replacementToken))
            .andExpect(status().isNotFound)

        val persistedHash = jdbcClient.sql("SELECT token_hash FROM calendar_subscription")
            .query(String::class.java)
            .single()
        assertThat(persistedHash).matches("^[0-9a-f]{64}$")
        assertThat(persistedHash).isNotIn(originalToken, replacementToken)
    }

    @Test
    fun `행렬 매개변수 형태의 내부 경로도 인증을 요구한다`() {
        listOf(
            "/internal/api/v1;ignored/subscriptions",
            "/internal;ignored/api/v1/subscriptions",
        ).forEach { path ->
            mockMvc.perform(
                post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"seasonId":"$SEASON_ID"}"""),
            )
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
            "bearer $INTERNAL_TOKEN",
            "BEARER   $PREVIOUS_INTERNAL_TOKEN",
        ).forEach { authorization ->
            mockMvc.perform(
                post("/internal/api/v1/projections/seasons/{seasonId}/rebuild", SEASON_ID)
                    .header(HttpHeaders.AUTHORIZATION, authorization),
            )
                .andExpect(status().isOk)
        }

        listOf(
            "Basic $INTERNAL_TOKEN",
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

    @Test
    fun `Spring MVC 요청 오류는 API 오류 응답 형태를 유지한다`() {
        val result = mockMvc.perform(authorizedPost("/internal/api/v1/subscriptions/not-a-uuid/rotate"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andReturn()

        ContractSchemaSupport.assertValid(
            "api-error.v1.schema.json",
            result.response.contentAsString,
            "실제 Spring MVC 오류 응답",
        )
    }

    @Test
    fun `타임스탬프 정밀도는 개정 번호 비교 전에 정규화한다`() {
        val initial = utcSnapshot(
            EVENT_1,
            revision = 0,
            summary = "Initial",
            sourceUpdatedAt = "2026-08-11T00:20:00.000000100Z",
        )
        val sameMicrosecond = utcSnapshot(
            EVENT_2,
            revision = 1,
            summary = "Too fine",
            sourceUpdatedAt = "2026-08-11T00:20:00.000000200Z",
        )
        val correctedRetry = utcSnapshot(
            EVENT_2,
            revision = 1,
            summary = "Valid precision",
            sourceUpdatedAt = "2026-08-11T00:20:00.000001100Z",
        )

        ingest(initial, "APPLIED")
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sameMicrosecond),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SOURCE_REVISION_CONFLICT"))

        ingest(correctedRetry, "APPLIED")
        ingest(correctedRetry, "DUPLICATE")
    }

    @Test
    fun `문서화한 일정 예시는 실제 수신 경로에서 생명주기를 따른다`() {
        ingest(Path.of("contracts/examples/schedule-snapshot.utc-active.json").readText(), "APPLIED")
        ingest(Path.of("contracts/examples/schedule-snapshot.utc-point-active.json").readText(), "APPLIED")
        ingest(Path.of("contracts/examples/schedule-snapshot.zoned-point-active.json").readText(), "APPLIED")
        ingest(Path.of("contracts/examples/schedule-snapshot.all-day-active.json").readText(), "APPLIED")
        ingest(Path.of("contracts/examples/schedule-snapshot.zoned-active-r0.json").readText(), "APPLIED")
        ingest(Path.of("contracts/examples/schedule-snapshot.zoned-active-r2.json").readText(), "APPLIED")
        val cancelled = Path.of("contracts/examples/schedule-snapshot.zoned-cancelled.json").readText()
        ingest(cancelled, "APPLIED")
        val reactivated = Path.of("contracts/examples/schedule-snapshot.zoned-reactivated.json").readText()
        ingest(reactivated, "APPLIED")
        ingest(reactivated, "DUPLICATE")

        val credential = mockMvc.perform(
            authorizedPost("/internal/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"seasonId":"f5316f93-d49e-4230-b1d0-9e9c2d079819"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString
        val token = credentialToken(credential)
        val event = mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsByteArray
            .parseIcalendar()
            .events()
            .single { it.requiredPropertyValue(Property.UID) == "b8ca471a-b228-42fa-8d41-28f05ee90d40@cal.baton" }

        assertThat(event.requiredPropertyValue(Property.SEQUENCE)).isEqualTo("4")
        assertThat(event.requiredPropertyValue(Property.STATUS)).isEqualTo("CONFIRMED")
    }

    @Test
    fun `알 수 없는 필드와 DST 공백의 현지 시각은 거부한다`() {
        val payload = utcSnapshot(EVENT_1, revision = 0, summary = "Unknown field")
        val withUnknownField = payload.dropLast(1) + ",\n\"unexpected\":true\n}"
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(withUnknownField),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        val dstGap = zonedSnapshot(
            startLocal = "2026-03-08T02:30:00",
            endLocal = "2026-03-08T03:30:00",
        )
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(dstGap),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    private fun ingest(payload: String, expectedResult: String) {
        val response = mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(expectedResult))
            .andReturn()
            .response
            .contentAsString

        ContractSchemaSupport.assertValid(
            "schedule-snapshot-result.v1.schema.json",
            response,
            "실제 일정 스냅샷 $expectedResult 응답",
        )
    }

    private fun credentialToken(json: String): String {
        val token: String = JsonPath.read(json, "$.token")
        val feedUrl: String = JsonPath.read(json, "$.feedUrl")

        assertThat(feedUrl).isEqualTo("$PUBLIC_BASE_URL/calendars/v1/$token.ics")
        return token
    }

    private fun authorizedPost(path: String, vararg uriVariables: Any) =
        post(path, *uriVariables).header(HttpHeaders.AUTHORIZATION, "Bearer $INTERNAL_TOKEN")

    private fun ingestionCount(result: String): Double = meterRegistry
        .get("baton.cal.snapshot.ingestion")
        .tag("result", result)
        .counter()
        .count()

    private fun authenticationCount(result: String): Double = meterRegistry
        .get("baton.cal.internal.authentication")
        .tag("result", result)
        .counter()
        .count()

    private fun utcSnapshot(
        eventId: String,
        revision: Int,
        summary: String,
        status: String = "ACTIVE",
        sourceUpdatedAt: String = when (revision) {
            0 -> "2026-08-11T00:20:00Z"
            1 -> "2026-08-11T00:30:00Z"
            2 -> "2026-08-11T00:40:00Z"
            else -> "2026-08-11T00:50:00Z"
        },
    ): String {
        return """
        {
          "eventId": "$eventId",
          "occurredAt": "2026-08-11T01:00:00Z",
          "sourceItemId": "$SOURCE_ITEM_ID",
          "seasonId": "$SEASON_ID",
          "revision": $revision,
          "status": "$status",
          "summary": "$summary",
          "description": "Confirmed by BATON",
          "location": "Seoul",
          "time": {
            "type": "UTC_INSTANT",
            "startInstant": "2026-09-01T01:00:00Z",
            "endInstant": "2026-09-01T02:00:00Z"
          },
          "sourceUpdatedAt": "$sourceUpdatedAt"
        }
        """.trimIndent()
    }

    private fun zonedSnapshot(
        startLocal: String = "2026-11-01T01:30:00",
        endLocal: String = "2026-11-01T02:30:00",
    ): String =
        """
        {
          "eventId": "$ZONED_EVENT_ID",
          "occurredAt": "2026-10-01T01:00:00Z",
          "sourceItemId": "$ZONED_SOURCE_ITEM_ID",
          "seasonId": "$SEASON_ID",
          "revision": 0,
          "status": "ACTIVE",
          "summary": "DST game",
          "description": null,
          "location": "New York",
          "time": {
            "type": "ZONED_LOCAL",
            "startLocal": "$startLocal",
            "endLocal": "$endLocal",
            "zoneId": "America/New_York"
          },
          "sourceUpdatedAt": "2026-10-01T00:30:00Z"
        }
        """.trimIndent()

    companion object {
        const val INTERNAL_TOKEN = "test-internal-token-that-is-long-enough"
        const val PREVIOUS_INTERNAL_TOKEN = "test-previous-internal-token-that-is-long-enough"
        const val INTERNAL_BEARER_CHALLENGE = "Bearer realm=\"baton-cal-internal\""
        const val SEASON_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val SOURCE_ITEM_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val ZONED_SOURCE_ITEM_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val EVENT_1 = "11111111-1111-1111-1111-111111111111"
        const val EVENT_2 = "22222222-2222-2222-2222-222222222222"
        const val EVENT_3 = "33333333-3333-3333-3333-333333333333"
        const val EVENT_4 = "44444444-4444-4444-4444-444444444444"
        const val EVENT_5 = "55555555-5555-5555-5555-555555555555"
        const val ZONED_EVENT_ID = "66666666-6666-6666-6666-666666666666"
        const val EVENT_7 = "77777777-7777-7777-7777-777777777777"
    }
}
