package io.baton.cal.web

import io.baton.cal.support.PostgreSqlTestContainer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.text.Normalizer
import java.util.UUID

@ImportTestcontainers(PostgreSqlTestContainer::class)
@AutoConfigureMockMvc
@Sql("/reset-database.sql")
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=test-internal-token-that-is-long-enough",
        "baton.cal.public-base-url=https://calendar.example.test",
    ],
)
class SnapshotInputContractTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    @Test
    fun `instant fields reject JSON number tokens`() {
        listOf(
            utcSnapshot(occurredAt = "1786410005"),
            utcSnapshot(sourceUpdatedAt = "1786410000"),
            utcSnapshot(startInstant = "1788051600"),
            utcSnapshot(endInstant = "1788055200"),
        ).forEach(::assertInvalid)
    }

    @Test
    fun `integer fields reject string coercion`() {
        assertInvalid(utcSnapshot().replace("\"revision\": 0", "\"revision\": \"0\""))
    }

    @Test
    fun `UUID 필드는 36자 표준 문자열만 허용한다`() {
        listOf(
            "AAAAAAAAAAAAAAAAAAAAAA",
            "AAAAAAAAAAAAAAAAAAAAAA==",
            "1-1-1-1-1",
            "00000000-00000-000-0000-000000000000",
        ).forEach { invalidUuid ->
            assertInvalid(utcSnapshot(eventId = invalidUuid))
        }
    }

    @Test
    fun `zoned local fields require seconds`() {
        listOf(
            zonedSnapshot(startLocal = "2026-08-31T23:30"),
            zonedSnapshot(endLocal = "2026-09-01T00:30"),
        ).forEach(::assertInvalid)
    }

    @Test
    fun `instant fields require RFC 3339 seconds`() {
        assertInvalid(utcSnapshot(occurredAt = quoted("2026-08-11T01:00Z")))
    }

    @Test
    fun `timestamp lexical forms are parsed strictly by the JDK formatter`() {
        listOf(
            utcSnapshot(occurredAt = quoted("+02026-08-11T01:00:05Z")),
            utcSnapshot(occurredAt = quoted("2026-08-11T01:00:05.Z")),
            zonedSnapshot(startLocal = "2026-08-31t23:30:00"),
            zonedSnapshot(startLocal = "2026-08-31T23:30:00."),
        ).forEach(::assertInvalid)
    }

    @Test
    fun `calendar-invalid timestamps return a contract error`() {
        assertInvalid(utcSnapshot(occurredAt = quoted("2026-13-11T01:00:05Z")))
        assertInvalid(zonedSnapshot(startLocal = "2026-02-30T10:00:00"))
    }

    @Test
    fun `timestamp errors do not reflect the rejected value`() {
        val sensitiveValue = "https://calendar.example.test/calendars/v1/secret.ics"
        mockMvc.perform(
            post(SNAPSHOT_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $INTERNAL_TOKEN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(utcSnapshot(occurredAt = quoted(sensitiveValue))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("snapshot contains an invalid timestamp"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(sensitiveValue))))
    }

    @Test
    fun `TEXT 길이는 Unicode 코드 포인트로 세고 NFC를 요구한다`() {
        val emoji = "😀"
        assertApplied(utcSnapshot(summary = emoji.repeat(512)))
        assertApplied(
            utcSnapshot(
                description = emoji.repeat(4096),
                location = emoji.repeat(512),
            ),
        )
        assertInvalid(utcSnapshot(summary = emoji.repeat(513)))
        assertInvalid(utcSnapshot(description = emoji.repeat(4097)))
        assertInvalid(utcSnapshot(location = emoji.repeat(513)))
        assertInvalid(utcSnapshot(summary = ""))
        assertInvalid(utcSnapshot(description = ""))
        assertInvalid(utcSnapshot(location = ""))
        assertInvalid(utcSnapshot(summary = Normalizer.normalize("é", Normalizer.Form.NFD)))
    }

    @Test
    fun `TEXT는 LF와 HTAB 및 보조 평면 Unicode를 허용한다`() {
        assertApplied(
            utcSnapshot(
                summary = "요약\\n다음 줄\\t😀\\uD83D\\uDE00",
                description = "설명\\n다음 줄\\t😀\\uD83D\\uDE00",
                location = "장소\\n다음 줄\\t😀\\uD83D\\uDE00",
            ),
        )
    }

    @Test
    fun `TEXT는 RFC 5545에서 허용하지 않는 제어 문자를 모든 필드에서 거부한다`() {
        listOf(
            utcSnapshot(summary = "앞\\u0000뒤"),
            utcSnapshot(summary = "앞\\u000B뒤"),
            utcSnapshot(summary = "앞\\u001F뒤"),
            utcSnapshot(summary = "앞\\u007F뒤"),
            utcSnapshot(description = "앞\\u0008뒤"),
            utcSnapshot(location = "앞\\u000D뒤"),
        ).forEach(::assertInvalid)
    }

    @Test
    fun `JSON 이스케이프로 전달된 짝이 없는 서로게이트를 모든 TEXT 필드에서 거부한다`() {
        listOf(
            utcSnapshot(summary = "앞\\uD800뒤"),
            utcSnapshot(summary = "앞\\uDC00뒤"),
            utcSnapshot(description = "앞\\uD800뒤"),
            utcSnapshot(location = "앞\\uDC00뒤"),
        ).forEach(::assertInvalid)
    }

    @Test
    fun `RFC3339 offset instants and fractional local seconds are accepted`() {
        assertApplied(
            utcSnapshot(
                occurredAt = quoted("2026-08-11T10:00:05+09:00"),
                sourceUpdatedAt = quoted("2026-08-11T10:00:00+09:00"),
                startInstant = quoted("2026-08-16T18:00:00+09:00"),
                endInstant = quoted("2026-08-16T19:30:00+09:00"),
            ),
        )
        assertApplied(
            zonedSnapshot(
                startLocal = "2026-08-31T23:30:00.1",
                endLocal = "2026-09-01T00:30:00.987654321",
            ),
        )
    }

    @Test
    fun `시간대는 iCal4j가 원문 식별자로 보존하는 IANA 지역만 허용한다`() {
        assertInvalid(zonedSnapshot(zoneId = "America/Coyhaique"))
        assertInvalid(zonedSnapshot(zoneId = "US/Eastern"))
        assertInvalid(zonedSnapshot(zoneId = "+09:00"))
    }

    @Test
    fun `시점과 종일 일정은 각 시간 형태의 경계를 지킨다`() {
        listOf(
            snapshotWithTime("""{"type":"UTC_POINT","atInstant":"2026-08-17T12:00Z"}"""),
            snapshotWithTime(
                """{"type":"ZONED_LOCAL_POINT","atLocal":"2026-03-08T02:30:00","zoneId":"America/New_York"}""",
            ),
            snapshotWithTime("""{"type":"ALL_DAY","startDate":"2026-02-30","endDate":"2026-03-01"}"""),
            snapshotWithTime("""{"type":"ALL_DAY","startDate":"2026-08-22","endDate":"2026-08-22"}"""),
        ).forEach(::assertInvalid)
    }

    private fun assertInvalid(payload: String) {
        mockMvc.perform(
            post(SNAPSHOT_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $INTERNAL_TOKEN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    private fun assertApplied(payload: String) {
        mockMvc.perform(
            post(SNAPSHOT_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $INTERNAL_TOKEN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("APPLIED"))
    }

    private fun utcSnapshot(
        eventId: String = UUID.randomUUID().toString(),
        occurredAt: String = quoted("2026-08-11T01:00:05Z"),
        sourceUpdatedAt: String = quoted("2026-08-11T01:00:00Z"),
        startInstant: String = quoted("2026-08-16T09:00:00Z"),
        endInstant: String = quoted("2026-08-16T10:30:00Z"),
        summary: String = "ROUND 1",
        description: String? = null,
        location: String? = null,
    ): String =
        """
        {
          "eventId": "$eventId",
          "occurredAt": $occurredAt,
          "sourceItemId": "${UUID.randomUUID()}",
          "seasonId": "$SEASON_ID",
          "revision": 0,
          "status": "ACTIVE",
          "summary": "$summary",
          "description": ${description?.let(::quoted) ?: "null"},
          "location": ${location?.let(::quoted) ?: "null"},
          "sourceUpdatedAt": $sourceUpdatedAt,
          "time": {
            "type": "UTC_INSTANT",
            "startInstant": $startInstant,
            "endInstant": $endInstant
          }
        }
        """.trimIndent()

    private fun zonedSnapshot(
        startLocal: String = "2026-08-31T23:30:00",
        endLocal: String = "2026-09-01T00:30:00",
        zoneId: String = "Asia/Seoul",
    ): String =
        """
        {
          "eventId": "${UUID.randomUUID()}",
          "occurredAt": "2026-08-11T01:00:05Z",
          "sourceItemId": "${UUID.randomUUID()}",
          "seasonId": "$SEASON_ID",
          "revision": 0,
          "status": "ACTIVE",
          "summary": "자정 경계 일정",
          "description": null,
          "location": null,
          "sourceUpdatedAt": "2026-08-11T01:00:00Z",
          "time": {
            "type": "ZONED_LOCAL",
            "startLocal": "$startLocal",
            "endLocal": "$endLocal",
            "zoneId": "$zoneId"
          }
        }
        """.trimIndent()

    private fun snapshotWithTime(time: String): String =
        """
        {
          "eventId": "${UUID.randomUUID()}",
          "occurredAt": "2026-08-11T01:00:05Z",
          "sourceItemId": "${UUID.randomUUID()}",
          "seasonId": "$SEASON_ID",
          "revision": 0,
          "status": "ACTIVE",
          "summary": "시간 형태 검증",
          "description": null,
          "location": null,
          "sourceUpdatedAt": "2026-08-11T01:00:00Z",
          "time": $time
        }
        """.trimIndent()

    private fun quoted(value: String): String = "\"$value\""

    private companion object {
        const val SNAPSHOT_PATH = "/internal/api/v1/schedule-snapshots"
        const val INTERNAL_TOKEN = "test-internal-token-that-is-long-enough"
        const val SEASON_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    }
}
