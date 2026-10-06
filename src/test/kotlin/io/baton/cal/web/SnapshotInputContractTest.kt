package io.baton.cal.web

import io.baton.cal.contract.andReturnValid
import io.baton.cal.support.CalIntegrationTest
import io.baton.cal.support.ingestSnapshot
import io.baton.cal.support.postSnapshot
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.argumentSet
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.text.Normalizer
import java.util.UUID

@CalIntegrationTest
class SnapshotInputContractTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    @ParameterizedTest
    @MethodSource("invalidSnapshots")
    fun `계약을 어긴 스냅샷은 저장하지 않고 400을 반환한다`(payloads: List<String>) {
        payloads.forEach(::assertInvalid)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0.5", "-0.5", "1e-1", "1.0", "1e0"])
    fun `개정 번호의 소수와 지수 표기는 저장하지 않고 정수로 수정한 요청은 처리한다`(revision: String) {
        val payload = utcSnapshot()
        assertInvalid(payload.replace("\"revision\": 0", "\"revision\": $revision"))
        mockMvc.ingestSnapshot(payload)
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(
        "summary, 123", "summary, 12.5", "summary, true",
        "description, 123", "description, 12.5", "description, true",
        "location, 123", "location, 12.5", "location, true",
    )
    fun `TEXT의 숫자와 불리언은 저장하지 않고 따옴표로 감싼 문자열은 처리한다`(field: String, value: String) {
        val payload = utcSnapshot(summary = value, description = value, location = value)
        assertInvalid(payload.replace("\"$field\": \"$value\"", "\"$field\": $value"))
        mockMvc.ingestSnapshot(payload)
    }

    @Test
    fun `UUID 필드는 대소문자를 구분하지 않는 36자 표준 문자열만 허용한다`() {
        mockMvc.ingestSnapshot(utcSnapshot(eventId = "01234567-89AB-CDEF-0123-456789ABCDEF"))

        listOf(
            "AAAAAAAAAAAAAAAAAAAAAA",
            "AAAAAAAAAAAAAAAAAAAAAA==",
            "1-1-1-1-1",
            "00000000-00000-000-0000-000000000000",
            "0123456789abcdef0123456789abcdef",
            " 01234567-89ab-cdef-0123-456789abcdef",
            "01234567-89ab-cdef-0123-456789abcdef ",
        ).forEach { invalidUuid ->
            assertInvalid(utcSnapshot(eventId = invalidUuid))
        }
    }

    @Test
    fun `timestamp errors do not reflect the rejected value`() {
        val sensitiveValue = "https://calendar.example.test/calendars/v1/secret.ics"
        mockMvc.postSnapshot(utcSnapshot(occurredAt = quoted(sensitiveValue)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("snapshot contains an invalid timestamp"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(sensitiveValue))))
    }

    @Test
    fun `TEXT 길이는 Unicode 코드 포인트로 세고 NFC를 요구한다`() {
        val emoji = "😀"
        mockMvc.ingestSnapshot(utcSnapshot(summary = emoji.repeat(512)))
        mockMvc.ingestSnapshot(
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
        mockMvc.ingestSnapshot(
            utcSnapshot(
                summary = "요약\\n다음 줄\\t😀\\uD83D\\uDE00",
                description = "설명\\n다음 줄\\t😀\\uD83D\\uDE00",
                location = "장소\\n다음 줄\\t😀\\uD83D\\uDE00",
            ),
        )
    }

    @Test
    fun `RFC3339 offset instants and fractional local seconds are accepted`() {
        mockMvc.ingestSnapshot(
            utcSnapshot(
                occurredAt = quoted("2026-08-11T10:00:05+09:00"),
                sourceUpdatedAt = quoted("2026-08-11T10:00:00+09:00"),
                startInstant = quoted("2026-08-16T18:00:00+09:00"),
                endInstant = quoted("2026-08-16T19:30:00+09:00"),
            ),
        )
        mockMvc.ingestSnapshot(
            zonedSnapshot(
                startLocal = "2026-08-31T23:30:00.1",
                endLocal = "2026-09-01T00:30:00.987654321",
            ),
        )
    }

    private fun assertInvalid(payload: String) {
        mockMvc.postSnapshot(payload)
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andReturnValid("api-error.v1.schema.json", "스냅샷 입력 오류 응답")
    }

    companion object {
        @JvmStatic
        fun invalidSnapshots(): List<Arguments> = listOf(
            argumentSet("잘린 JSON", listOf("""{"seasonId":""")),
            argumentSet(
                "시각 필드의 JSON 숫자",
                listOf(
                    utcSnapshot(occurredAt = "1786410005"),
                    utcSnapshot(sourceUpdatedAt = "1786410000"),
                    utcSnapshot(startInstant = "1788051600"),
                    utcSnapshot(endInstant = "1788055200"),
                ),
            ),
            argumentSet("정수 필드의 문자열", listOf(utcSnapshot().replace("\"revision\": 0", "\"revision\": \"0\""))),
            argumentSet(
                "초가 없는 시각",
                listOf(
                    utcSnapshot(occurredAt = quoted("2026-08-11T01:00Z")),
                    zonedSnapshot(startLocal = "2026-08-31T23:30"),
                    zonedSnapshot(endLocal = "2026-09-01T00:30"),
                ),
            ),
            argumentSet(
                "JDK 형식보다 엄격한 시각 어휘",
                listOf(
                    utcSnapshot(occurredAt = quoted("+02026-08-11T01:00:05Z")),
                    utcSnapshot(occurredAt = quoted("2026-08-11T01:00:05.Z")),
                    zonedSnapshot(startLocal = "2026-08-31t23:30:00"),
                    zonedSnapshot(startLocal = "2026-08-31T23:30:00."),
                ),
            ),
            argumentSet(
                "달력에 없는 날짜",
                listOf(
                    utcSnapshot(occurredAt = quoted("2026-13-11T01:00:05Z")),
                    zonedSnapshot(startLocal = "2026-02-30T10:00:00"),
                ),
            ),
            argumentSet(
                "RFC 5545가 허용하지 않는 TEXT 제어 문자",
                listOf(
                    utcSnapshot(summary = "앞\\u0000뒤"),
                    utcSnapshot(summary = "앞\\u000B뒤"),
                    utcSnapshot(summary = "앞\\u001F뒤"),
                    utcSnapshot(summary = "앞\\u007F뒤"),
                    utcSnapshot(description = "앞\\u0008뒤"),
                    utcSnapshot(location = "앞\\u000D뒤"),
                ),
            ),
            argumentSet(
                "JSON 이스케이프로 전달된 짝이 없는 서로게이트",
                listOf(
                    utcSnapshot(summary = "앞\\uD800뒤"),
                    utcSnapshot(summary = "앞\\uDC00뒤"),
                    utcSnapshot(description = "앞\\uD800뒤"),
                    utcSnapshot(location = "앞\\uDC00뒤"),
                ),
            ),
            argumentSet(
                "iCal4j가 원문 식별자로 보존하지 못하는 시간대",
                listOf(
                    zonedSnapshot(zoneId = "America/Coyhaique"),
                    zonedSnapshot(zoneId = "US/Eastern"),
                    zonedSnapshot(zoneId = "+09:00"),
                ),
            ),
            argumentSet(
                "시점과 종일 일정의 경계",
                listOf(
                    snapshotWithTime("""{"type":"UTC_POINT","atInstant":"2026-08-17T12:00Z"}"""),
                    snapshotWithTime(
                        """{"type":"ZONED_LOCAL_POINT","atLocal":"2026-03-08T02:30:00","zoneId":"America/New_York"}""",
                    ),
                    snapshotWithTime("""{"type":"ALL_DAY","startDate":"2026-02-30","endDate":"2026-03-01"}"""),
                    snapshotWithTime("""{"type":"ALL_DAY","startDate":"2026-08-22","endDate":"2026-08-22"}"""),
                ),
            ),
            argumentSet("알 수 없는 필드", listOf(utcSnapshot().dropLast(1) + ",\n\"unexpected\": true\n}")),
            argumentSet(
                "DST 공백의 현지 시각",
                listOf(
                    zonedSnapshot(
                        startLocal = "2026-03-08T02:30:00",
                        endLocal = "2026-03-08T03:30:00",
                        zoneId = "America/New_York",
                    ),
                ),
            ),
        )
    }
}

private const val SEASON_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

private fun utcSnapshot(
    eventId: String = UUID.randomUUID().toString(),
    occurredAt: String = quoted("2026-08-11T01:00:05Z"),
    sourceUpdatedAt: String = quoted("2026-08-11T01:00:00Z"),
    startInstant: String = quoted("2026-08-16T09:00:00Z"),
    endInstant: String = quoted("2026-08-16T10:30:00Z"),
    summary: String = "ROUND 1",
    description: String? = null,
    location: String? = null,
): String = snapshotWithTime(
    """{"type":"UTC_INSTANT","startInstant":$startInstant,"endInstant":$endInstant}""",
    eventId = eventId,
    occurredAt = occurredAt,
    sourceUpdatedAt = sourceUpdatedAt,
    summary = summary,
    description = description,
    location = location,
)

private fun zonedSnapshot(
    startLocal: String = "2026-08-31T23:30:00",
    endLocal: String = "2026-09-01T00:30:00",
    zoneId: String = "Asia/Seoul",
): String = snapshotWithTime(
    """{"type":"ZONED_LOCAL","startLocal":"$startLocal","endLocal":"$endLocal","zoneId":"$zoneId"}""",
)

private fun snapshotWithTime(
    time: String,
    eventId: String = UUID.randomUUID().toString(),
    occurredAt: String = quoted("2026-08-11T01:00:05Z"),
    sourceUpdatedAt: String = quoted("2026-08-11T01:00:00Z"),
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
      "time": $time
    }
    """.trimIndent()

private fun quoted(value: String): String = "\"$value\""
