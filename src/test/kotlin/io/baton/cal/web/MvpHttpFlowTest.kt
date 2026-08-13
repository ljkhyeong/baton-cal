package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import io.baton.cal.calendar.events
import io.baton.cal.calendar.parseIcalendar
import io.baton.cal.calendar.requiredPropertyValue
import io.baton.cal.calendar.timeZones
import net.fortuna.ical4j.model.Property
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
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
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=test-internal-token-that-is-long-enough",
        "baton.cal.public-base-url=https://calendar.example.test",
    ],
)
@Sql("/reset-database.sql")
class MvpHttpFlowTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbcClient: JdbcClient,
) {

    @Test
    fun `MVP supports idempotent snapshots conditional feeds rebuild and token lifecycle`() {
        mockMvc.perform(
            post("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(utcSnapshot(EVENT_1, revision = 1, summary = "Opening")),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))

        ingest(utcSnapshot(EVENT_1, revision = 1, summary = "Opening"), "APPLIED")
        ingest(utcSnapshot(EVENT_1, revision = 1, summary = "Opening"), "DUPLICATE")
        ingest(utcSnapshot(EVENT_2, revision = 1, summary = "Opening"), "DUPLICATE")
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(utcSnapshot(EVENT_2, revision = 2, summary = "reused envelope")),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("EVENT_ID_CONFLICT"))
        ingest(utcSnapshot(EVENT_3, revision = 0, summary = "Old delivery"), "STALE")

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
            .andExpect(jsonPath("$.token").value(matchesPattern("^[A-Za-z0-9_-]{43}$")))
            .andExpect(
                jsonPath("$.feedUrl").value(matchesPattern("^https://calendar\\.example\\.test/calendars/v1/.+\\.ics$")),
            )
            .andReturn()

        val createJson = createResult.response.contentAsString
        val subscriptionId: String = JsonPath.read(createJson, "$.subscriptionId")
        val originalToken: String = JsonPath.read(createJson, "$.token")

        val firstFeed = mockMvc.perform(get("/calendars/v1/{token}.ics", originalToken))
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/calendar;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(header().exists(HttpHeaders.LAST_MODIFIED))
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
            .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
            .andExpect(content().bytes(byteArrayOf()))

        mockMvc.perform(
            get("/calendars/v1/{token}.ics", originalToken)
                .header(HttpHeaders.IF_MODIFIED_SINCE, originalLastModified),
        )
            .andExpect(status().isNotModified)
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

        mockMvc.perform(authorizedPost("/internal/api/v1/projections/seasons/{seasonId}/rebuild", SEASON_ID))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.etag").value(refreshedEtag))
            .andExpect(jsonPath("$.itemCount").value(2))

        mockMvc.perform(get("/calendars/v1/{token}.ics", originalToken))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, refreshedEtag))
            .andExpect(header().string(HttpHeaders.LAST_MODIFIED, refreshedLastModified))
            .andExpect(content().bytes(refreshedBytes))

        val rotateResult = mockMvc.perform(
            authorizedPost("/internal/api/v1/subscriptions/{subscriptionId}/rotate", subscriptionId),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.token").value(matchesPattern("^[A-Za-z0-9_-]{43}$")))
            .andReturn()
        val replacementToken: String = JsonPath.read(rotateResult.response.contentAsString, "$.token")
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

        val persistedHashes = jdbcClient.sql("SELECT token_hash FROM calendar_subscription")
            .query(String::class.java)
            .list()
        assertThat(persistedHashes).hasSize(1)
        assertThat(persistedHashes.single()).matches("^[0-9a-f]{64}$")
        assertThat(persistedHashes.single()).isNotIn(originalToken, replacementToken)
    }

    @Test
    fun `matrix-parameter variants of internal routes still require authentication`() {
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
    fun `Spring MVC request errors keep the API error envelope`() {
        mockMvc.perform(authorizedPost("/internal/api/v1/subscriptions/not-a-uuid/rotate"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `timestamp precision is canonicalized before revision comparison`() {
        val initial = withSourceUpdatedAt(
            utcSnapshot(EVENT_1, revision = 0, summary = "Initial"),
            "2026-08-11T00:20:00.000000100Z",
        )
        val sameMicrosecond = withSourceUpdatedAt(
            utcSnapshot(EVENT_2, revision = 1, summary = "Too fine"),
            "2026-08-11T00:20:00.000000200Z",
        )
        val correctedRetry = withSourceUpdatedAt(
            utcSnapshot(EVENT_2, revision = 1, summary = "Valid precision"),
            "2026-08-11T00:20:00.000001100Z",
        )

        ingest(initial, "APPLIED")
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(sameMicrosecond),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SOURCE_REVISION_CONFLICT"))

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
    fun `documented snapshot examples are accepted by the running contract`() {
        ingest(Files.readString(Path.of("contracts/examples/schedule-snapshot.utc-active.json")), "APPLIED")
        ingest(Files.readString(Path.of("contracts/examples/schedule-snapshot.zoned-cancelled.json")), "APPLIED")
    }

    @Test
    fun `unknown fields and DST gap wall times are rejected`() {
        val payload = utcSnapshot(EVENT_1, revision = 0, summary = "Unknown field")
        val withUnknownField = payload.dropLast(1) + ",\n\"unexpected\":true\n}"
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(withUnknownField),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        val dstGap = zonedSnapshot()
            .replace("2026-11-01T01:30:00", "2026-03-08T02:30:00")
            .replace("2026-11-01T02:30:00", "2026-03-08T03:30:00")
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(dstGap),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    private fun ingest(payload: String, expectedResult: String) {
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(expectedResult))
    }

    private fun authorizedPost(path: String, vararg uriVariables: Any) =
        post(path, *uriVariables).header(HttpHeaders.AUTHORIZATION, "Bearer $INTERNAL_TOKEN")

    private fun withSourceUpdatedAt(payload: String, sourceUpdatedAt: String): String {
        val marker = "\"sourceUpdatedAt\": \""
        val valueStart = payload.indexOf(marker) + marker.length
        require(valueStart >= marker.length) { "sourceUpdatedAt field is missing" }
        val valueEnd = payload.indexOf('"', valueStart)
        return payload.replaceRange(valueStart, valueEnd, sourceUpdatedAt)
    }

    private fun utcSnapshot(
        eventId: String,
        revision: Int,
        summary: String,
        status: String = "ACTIVE",
    ): String {
        val sourceUpdatedAt = when (revision) {
            0 -> "2026-08-11T00:20:00Z"
            1 -> "2026-08-11T00:30:00Z"
            2 -> "2026-08-11T00:40:00Z"
            else -> "2026-08-11T00:50:00Z"
        }
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
        const val INTERNAL_TOKEN = "test-internal-token-that-is-long-enough"
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

        @Container
        @ServiceConnection
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18.4-alpine")
    }
}
