package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import io.baton.cal.calendar.events
import io.baton.cal.calendar.parseIcalendar
import io.baton.cal.calendar.requiredPropertyValue
import io.baton.cal.calendar.timeZones
import io.baton.cal.contract.andReturnValid
import io.baton.cal.contract.contractExample
import io.baton.cal.support.INTERNAL_BEARER_CHALLENGE
import io.baton.cal.support.CalIntegrationTest
import io.baton.cal.support.SNAPSHOT_PATH
import io.baton.cal.support.TEST_PUBLIC_BASE_URL
import io.baton.cal.support.authorizedDelete
import io.baton.cal.support.authorizedGet
import io.baton.cal.support.authorizedPost
import io.baton.cal.support.authorizedPut
import io.baton.cal.support.createSubscription
import io.baton.cal.support.ingestSnapshot
import io.baton.cal.support.ingestSnapshotExample
import io.baton.cal.support.jsonContent
import io.baton.cal.support.postSnapshot
import io.micrometer.core.instrument.MeterRegistry
import net.fortuna.ical4j.model.Property
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.jdbc.JdbcTestUtils
import java.util.UUID

@CalIntegrationTest
class MvpHttpFlowTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbcClient: JdbcClient,
    private val meterRegistry: MeterRegistry,
) {

    @Test
    fun `미리 정한 구독 ID로 응답 유실을 복구하고 재전달로 토큰이나 폐기 상태를 바꾸지 않는다`() {
        val subscriptionId = UUID.randomUUID().toString()
        val path = "/internal/api/v1/subscriptions/$subscriptionId"
        fun createRequest(seasonId: String = SEASON_ID) = authorizedPut(path).jsonContent("""{"seasonId":"$seasonId"}""")

        mockMvc.perform(put(path).jsonContent("""{"seasonId":"$SEASON_ID"}"""))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(authorizedGet(path))
            .andExpect(status().isNotFound)
            .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        val first = mockMvc.perform(createRequest())
            .andExpect(status().isCreated)
            .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.subscriptionId").value(subscriptionId))
            .andReturnValid("subscription-credential.v1.schema.json", "ID 지정 구독 생성 응답")
        val originalToken: String = JsonPath.read(first, "$.token")

        // 첫 응답을 받지 못한 호출자도 미리 저장한 ID만으로 기존 구독을 확인할 수 있다.
        mockMvc.perform(createRequest())
            .andExpect(status().isConflict)
            .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(content().json(contractExample("api-error.subscription-already-exists.json")))
            .andReturnValid("api-error.v1.schema.json", "중복 구독 생성 응답")
        mockMvc.perform(authorizedGet(path))
            .andExpect(status().isOk)
            .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.seasonId").value(SEASON_ID))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
        mockMvc.perform(get("/calendars/v1/$originalToken.ics")).andExpect(status().isOk)

        val differentSeasonId = UUID.randomUUID().toString()
        mockMvc.perform(createRequest(differentSeasonId))
            .andExpect(status().isConflict)
            .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(content().json(contractExample("api-error.subscription-scope-conflict.json")))
            .andReturnValid("api-error.v1.schema.json", "구독 시즌 충돌 응답")
        assertThat(JdbcTestUtils.countRowsInTable(jdbcClient, "calendar_subscription")).isEqualTo(1)
        assertThat(JdbcTestUtils.countRowsInTable(jdbcClient, "season_feed_projection")).isEqualTo(1)

        val rotated = mockMvc.perform(authorizedPost("$path/rotate"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val replacementToken: String = JsonPath.read(rotated, "$.token")
        mockMvc.perform(get("/calendars/v1/$originalToken.ics")).andExpect(status().isNotFound)
        mockMvc.perform(get("/calendars/v1/$replacementToken.ics")).andExpect(status().isOk)
        mockMvc.perform(createRequest()).andExpect(status().isConflict)
        mockMvc.perform(get("/calendars/v1/$replacementToken.ics")).andExpect(status().isOk)

        mockMvc.perform(authorizedDelete(path))
            .andExpect(status().isNoContent)
        mockMvc.perform(createRequest()).andExpect(status().isConflict)
        mockMvc.perform(authorizedGet(path)).andExpect(jsonPath("$.status").value("REVOKED"))
        mockMvc.perform(get("/calendars/v1/$replacementToken.ics")).andExpect(status().isNotFound)
    }

    @Test
    fun `복구 모드가 아니면 새 복구 매니페스트를 검증하지 않는다`() {
        mockMvc.perform(
            authorizedPut(
                "/internal/api/v1/recovery-runs/{recoveryId}/seasons/{seasonId}/manifest",
                UUID.randomUUID(),
                SEASON_ID,
            ).jsonContent(
                """
                {
                  "itemCount": 0,
                  "itemDigest": "${"0".repeat(64)}",
                  "metadataRevision": null,
                  "metadataDigest": null
                }
                """.trimIndent(),
            ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RECOVERY_MODE_REQUIRED"))
    }

    @Test
    fun `MVP는 멱등 스냅샷과 조건부 피드 재구축 및 토큰 수명주기를 지원한다`() {
        val appliedBefore = ingestionCount("applied")
        val duplicateBefore = ingestionCount("duplicate")
        val staleBefore = ingestionCount("stale")

        mockMvc.perform(post(SNAPSHOT_PATH).jsonContent(utcSnapshot(EVENT_1, revision = 1, summary = "Opening")))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, INTERNAL_BEARER_CHALLENGE))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))

        mockMvc.ingestSnapshot(utcSnapshot(EVENT_1, revision = 1, summary = "Opening"))
        mockMvc.ingestSnapshot(utcSnapshot(EVENT_1, revision = 1, summary = "Opening"), "DUPLICATE")
        mockMvc.ingestSnapshot(utcSnapshot(EVENT_2, revision = 1, summary = "Opening"), "DUPLICATE")
        assertThat(ingestionCount("applied")).isEqualTo(appliedBefore + 1)
        assertThat(ingestionCount("duplicate")).isEqualTo(duplicateBefore + 2)
        mockMvc.postSnapshot(utcSnapshot(EVENT_2, revision = 2, summary = "reused envelope"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("EVENT_ID_CONFLICT"))
        mockMvc.ingestSnapshot(utcSnapshot(EVENT_3, revision = 0, summary = "Old delivery"), "STALE")
        assertThat(ingestionCount("stale")).isEqualTo(staleBefore + 1)

        mockMvc.ingestSnapshot(
            utcSnapshot(
                eventId = EVENT_4,
                revision = 2,
                summary = "Opening cancelled",
                status = "CANCELLED",
            ),
        )
        mockMvc.postSnapshot(utcSnapshot(EVENT_5, revision = 2, summary = "conflicting revision"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SOURCE_REVISION_CONFLICT"))

        mockMvc.ingestSnapshot(zonedSnapshot())

        val createJson = mockMvc.createSubscription(SEASON_ID)
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

        listOf(HttpHeaders.IF_NONE_MATCH to "W/$originalEtag", HttpHeaders.IF_MODIFIED_SINCE to originalLastModified)
            .forEach { (validator, value) ->
                mockMvc.perform(get("/calendars/v1/{token}.ics", originalToken).header(validator, value))
                    .andExpect(status().isNotModified)
                    .andExpect(header().string(HttpHeaders.ETAG, originalEtag))
                    .andExpect(header().string(HttpHeaders.LAST_MODIFIED, originalLastModified))
                    .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
                    .andExpect(content().bytes(byteArrayOf()))
            }

        mockMvc.perform(
            get("/calendars/v1/{token}.ics", originalToken)
                .header(HttpHeaders.IF_NONE_MATCH, "\"different\"")
                .header(HttpHeaders.IF_MODIFIED_SINCE, "Wed, 31 Dec 2099 23:59:59 GMT"),
        )
            .andExpect(status().isOk)

        // Last-Modified는 초 단위 보조 검증 값이므로 같은 초의 변경은 강한 ETag로 판정한다.
        mockMvc.ingestSnapshot(
            utcSnapshot(
                eventId = EVENT_7,
                revision = 3,
                summary = "Cancellation corrected",
                status = "CANCELLED",
            ),
        )
        val refreshedFeed = mockMvc.perform(
            get("/calendars/v1/{token}.ics", originalToken)
                .header(HttpHeaders.IF_NONE_MATCH, originalEtag),
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

        mockMvc.perform(authorizedPost("/internal/api/v1/projections/seasons/{seasonId}/rebuild", SEASON_ID))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.etag").value(refreshedEtag))
            .andExpect(jsonPath("$.itemCount").value(2))
            .andReturnValid("projection-rebuild-result.v1.schema.json", "실제 시즌 투영 재구축 응답")

        mockMvc.perform(get("/calendars/v1/{token}.ics", originalToken))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, refreshedEtag))
            .andExpect(header().string(HttpHeaders.LAST_MODIFIED, refreshedLastModified))
            .andExpect(content().bytes(refreshedBytes))

        val rotateJson = mockMvc
            .perform(authorizedPost("/internal/api/v1/subscriptions/{subscriptionId}/rotate", subscriptionId))
            .andExpect(status().isOk)
            .andReturnValid("subscription-credential.v1.schema.json", "실제 구독 회전 응답")
        val replacementToken = credentialToken(rotateJson)
        assertThat(replacementToken).isNotEqualTo(originalToken)

        mockMvc.perform(get("/calendars/v1/{token}.ics", originalToken))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/calendars/v1/{token}.ics", replacementToken))
            .andExpect(status().isOk)
            .andExpect(content().bytes(refreshedBytes))

        mockMvc.perform(authorizedDelete("/internal/api/v1/subscriptions/{subscriptionId}", subscriptionId))
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
    fun `같은 원본 항목을 다른 시즌에 재사용하면 범위 충돌을 반환한다`() {
        mockMvc.ingestSnapshot(utcSnapshot(EVENT_1, revision = 1, summary = "Opening"))

        mockMvc.postSnapshot(utcSnapshot(EVENT_2, revision = 2, summary = "Moved opening", seasonId = OTHER_SEASON_ID))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SOURCE_ITEM_SCOPE_CONFLICT"))
            .andReturnValid("api-error.v1.schema.json", "원본 항목 시즌 범위 충돌 응답")
    }

    @Test
    fun `내부 경로 UUID는 36자 표준 문자열만 허용한다`() {
        listOf(
            authorizedGet("/internal/api/v1/calendar-items/1-1-1-1-1"),
            authorizedGet("/internal/api/v1/subscriptions/1-1-1-1-1"),
            authorizedPost("/internal/api/v1/subscriptions/1-1-1-1-1/rotate"),
            authorizedPut("/internal/api/v1/subscriptions/1-1-1-1-1").jsonContent("""{"seasonId":"$SEASON_ID"}"""),
            authorizedDelete("/internal/api/v1/subscriptions/1-1-1-1-1"),
            authorizedPost("/internal/api/v1/projections/seasons/1-1-1-1-1/rebuild"),
        ).forEach { request ->
            mockMvc.perform(request)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andReturnValid("api-error.v1.schema.json", "표준 UUID 형식이 아닌 내부 경로 오류 응답")
        }
    }

    @Test
    fun `일정 상태 조회는 중복과 역순 수신 뒤에도 채택한 개정 번호를 반환한다`() {
        val current = utcSnapshot(
            EVENT_1,
            revision = 2,
            summary = "변경된 일정",
            sourceUpdatedAt = "2026-08-11T00:40:00.123456100Z",
        )
        mockMvc.ingestSnapshot(current)
        mockMvc.ingestSnapshot(current, "DUPLICATE")
        mockMvc.ingestSnapshot(utcSnapshot(EVENT_2, revision = 1, summary = "이전 일정"), "STALE")

        mockMvc.perform(authorizedGet("/internal/api/v1/calendar-items/$SOURCE_ITEM_ID"))
            .andExpect(status().isOk)
            .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.sourceItemId").value(SOURCE_ITEM_ID))
            .andExpect(jsonPath("$.seasonId").value(SEASON_ID))
            .andExpect(jsonPath("$.revision").value(2))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.sourceUpdatedAt").value("2026-08-11T00:40:00.123456Z"))
            .andReturnValid("calendar-item-status.v1.schema.json", "중복과 역순 수신 뒤 채택한 일정 상태 응답")
    }

    @Test
    fun `구독 상태 조회는 세대 불일치와 회전 및 폐기를 구분하고 비밀 필드를 반환하지 않는다`() {
        val subscriptionId: String = JsonPath.read(mockMvc.createSubscription(SEASON_ID), "$.subscriptionId")
        jdbcClient.sql("UPDATE calendar_subscription SET credential_generation = :generation WHERE id = :id")
            .param("generation", UUID.fromString("88888888-8888-8888-8888-888888888888"))
            .param("id", UUID.fromString(subscriptionId))
            .update()

        mockMvc.perform(authorizedGet("/internal/api/v1/subscriptions/$subscriptionId"))
            .andExpect(status().isOk)
            .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.subscriptionId").value(subscriptionId))
            .andExpect(jsonPath("$.seasonId").value(SEASON_ID))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.generationMatches").value(false))
            .andReturnValid("subscription-status.v1.schema.json", "이전 세대 구독의 비밀 필드 없는 상태 응답")

        mockMvc.perform(authorizedPost("/internal/api/v1/subscriptions/$subscriptionId/rotate"))
            .andExpect(status().isOk)
        mockMvc.perform(authorizedGet("/internal/api/v1/subscriptions/$subscriptionId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.generationMatches").value(true))

        mockMvc.perform(authorizedDelete("/internal/api/v1/subscriptions/$subscriptionId"))
            .andExpect(status().isNoContent)
        mockMvc.perform(authorizedGet("/internal/api/v1/subscriptions/$subscriptionId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REVOKED"))
            .andExpect(jsonPath("$.generationMatches").value(true))
            .andReturnValid("subscription-status.v1.schema.json", "폐기된 구독의 비밀 필드 없는 상태 응답")
    }

    @Test
    fun `내부 상태 조회는 인증을 요구하고 없는 자원은 공통 오류로 반환한다`() {
        listOf(
            "/internal/api/v1/calendar-items/$SOURCE_ITEM_ID",
            "/internal/api/v1/subscriptions/$SOURCE_ITEM_ID",
        ).forEach { path ->
            mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized)
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, INTERNAL_BEARER_CHALLENGE))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))

            mockMvc.perform(authorizedGet(path))
                .andExpect(status().isNotFound)
                .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andReturnValid("api-error.v1.schema.json", "존재하지 않는 내부 자원의 상태 조회 응답")
        }
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

        mockMvc.ingestSnapshot(initial)
        mockMvc.postSnapshot(sameMicrosecond)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SOURCE_REVISION_CONFLICT"))

        mockMvc.ingestSnapshot(correctedRetry)
        mockMvc.ingestSnapshot(correctedRetry, "DUPLICATE")
    }

    @Test
    fun `문서화한 일정 예시는 실제 수신 경로에서 생명주기를 따른다`() {
        mockMvc.ingestSnapshotExample("schedule-snapshot.utc-active.json")
        mockMvc.ingestSnapshotExample("schedule-snapshot.utc-point-active.json")
        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-point-active.json")
        mockMvc.ingestSnapshotExample("schedule-snapshot.all-day-active.json")
        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-active-r0.json")
        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-active-r2.json")
        val cancelled = contractExample("schedule-snapshot.zoned-cancelled.json")
        mockMvc.ingestSnapshot(cancelled)
        mockMvc.perform(authorizedGet("/internal/api/v1/calendar-items/$ZONED_CONTRACT_SOURCE_ITEM_ID"))
            .andExpect(status().isOk)
            .andExpect(content().json(contractExample("calendar-item-status.cancelled.json")))
            .andReturnValid("calendar-item-status.v1.schema.json", "문서화한 취소 일정 상태 응답")
        val reactivated = contractExample("schedule-snapshot.zoned-reactivated.json")
        mockMvc.ingestSnapshot(reactivated)
        mockMvc.ingestSnapshot(reactivated, "DUPLICATE")

        val token = credentialToken(mockMvc.createSubscription("f5316f93-d49e-4230-b1d0-9e9c2d079819"))
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

    private fun credentialToken(json: String): String {
        val token: String = JsonPath.read(json, "$.token")
        val feedUrl: String = JsonPath.read(json, "$.feedUrl")

        assertThat(feedUrl).isEqualTo("$TEST_PUBLIC_BASE_URL/calendars/v1/$token.ics")
        return token
    }

    private fun ingestionCount(result: String) = counterValue("baton.cal.snapshot.ingestion", result)

    private fun counterValue(name: String, result: String): Double =
        meterRegistry.get(name).tag("result", result).counter().count()

    private fun utcSnapshot(
        eventId: String,
        revision: Int,
        summary: String,
        status: String = "ACTIVE",
        seasonId: String = SEASON_ID,
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
          "seasonId": "$seasonId",
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

    private fun zonedSnapshot(): String =
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
            "startLocal": "2026-11-01T01:30:00",
            "endLocal": "2026-11-01T02:30:00",
            "zoneId": "America/New_York"
          },
          "sourceUpdatedAt": "2026-10-01T00:30:00Z"
        }
        """.trimIndent()

    companion object {
        const val SEASON_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val OTHER_SEASON_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val SOURCE_ITEM_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val ZONED_SOURCE_ITEM_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val ZONED_CONTRACT_SOURCE_ITEM_ID = "b8ca471a-b228-42fa-8d41-28f05ee90d40"
        const val EVENT_1 = "11111111-1111-1111-1111-111111111111"
        const val EVENT_2 = "22222222-2222-2222-2222-222222222222"
        const val EVENT_3 = "33333333-3333-3333-3333-333333333333"
        const val EVENT_4 = "44444444-4444-4444-4444-444444444444"
        const val EVENT_5 = "55555555-5555-5555-5555-555555555555"
        const val ZONED_EVENT_ID = "66666666-6666-6666-6666-666666666666"
        const val EVENT_7 = "77777777-7777-7777-7777-777777777777"
    }
}
