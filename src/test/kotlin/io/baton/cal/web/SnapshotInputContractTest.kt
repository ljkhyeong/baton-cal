package io.baton.cal.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

@Testcontainers
@AutoConfigureMockMvc
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
    fun `calendar-invalid timestamps return a contract error`() {
        assertInvalid(utcSnapshot(occurredAt = quoted("2026-13-11T01:00:05Z")))
        assertInvalid(zonedSnapshot(startLocal = "2026-02-30T10:00:00"))
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
    fun `zone must be supported by both Java and the calendar renderer`() {
        assertInvalid(zonedSnapshot(zoneId = "America/Coyhaique"))
        assertInvalid(zonedSnapshot(zoneId = "+09:00"))
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
        occurredAt: String = quoted("2026-08-11T01:00:05Z"),
        sourceUpdatedAt: String = quoted("2026-08-11T01:00:00Z"),
        startInstant: String = quoted("2026-08-16T09:00:00Z"),
        endInstant: String = quoted("2026-08-16T10:30:00Z"),
    ): String =
        """
        {
          "eventId": "${UUID.randomUUID()}",
          "occurredAt": $occurredAt,
          "sourceItemId": "${UUID.randomUUID()}",
          "seasonId": "$SEASON_ID",
          "revision": 0,
          "status": "ACTIVE",
          "summary": "ROUND 1",
          "description": null,
          "location": null,
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

    private fun quoted(value: String): String = "\"$value\""

    private companion object {
        const val SNAPSHOT_PATH = "/internal/api/v1/schedule-snapshots"
        const val INTERNAL_TOKEN = "test-internal-token-that-is-long-enough"
        const val SEASON_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

        @Container
        @ServiceConnection
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18.4-alpine")
    }
}
