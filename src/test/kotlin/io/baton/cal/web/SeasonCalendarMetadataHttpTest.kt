package io.baton.cal.web

import io.baton.cal.calendar.calendarName
import com.jayway.jsonpath.JsonPath
import io.baton.cal.calendar.events
import io.baton.cal.calendar.parseIcalendar
import io.baton.cal.calendar.requiredEvent
import io.baton.cal.contract.andReturnValid
import io.baton.cal.contract.contractExample
import io.baton.cal.support.CalIntegrationTest
import io.baton.cal.support.authorizedPost
import io.baton.cal.support.createSubscription
import io.baton.cal.support.ingestSnapshotExample
import io.baton.cal.support.seasonCalendarMetadataRequest
import io.baton.cal.support.updateSeasonCalendarMetadata
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@CalIntegrationTest
class SeasonCalendarMetadataHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val meterRegistry: MeterRegistry,
) {
    @Test
    fun `이름 변경은 개정 번호를 따르고 일정 표현과 구독을 유지한다`() {
        val initial = contractExample("season-calendar-metadata.r0.json")
        val updated = contractExample("season-calendar-metadata.r2.json")
        val initialResponse = update(initial)
        assertThat(JsonPath.read<Int>(initialResponse, "$.revision")).isZero()

        val token: String = JsonPath.read(mockMvc.createSubscription(SEASON_ID), "$.token")
        val emptyFeed = mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray.parseIcalendar()
        assertThat(emptyFeed.events()).isEmpty()
        assertThat(emptyFeed.calendarName()).isEqualTo("BATON 개발 시즌")

        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-active-r0.json")
        val before = mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isOk)
            .andReturn().response
        assertThat(before.contentAsByteArray.parseIcalendar().calendarName()).isEqualTo("BATON 개발 시즌")

        val updatedResponse = update(updated)
        assertThat(updatedResponse).isEqualTo(update(updated))
        assertThat(updatedResponse).isEqualTo(update(initial))

        val after = mockMvc.perform(
            get("/calendars/v1/{token}.ics", token)
                .header(HttpHeaders.IF_NONE_MATCH, requireNotNull(before.getHeader(HttpHeaders.ETAG))),
        )
            .andExpect(status().isOk)
            .andReturn().response
        val renamed = after.contentAsByteArray.parseIcalendar()
        assertThat(renamed.calendarName()).isEqualTo("BATON 가을 개발 시즌")
        assertThat(renamed.requiredEvent().toString())
            .isEqualTo(before.contentAsByteArray.parseIcalendar().requiredEvent().toString())

        mockMvc.perform(seasonCalendarMetadataRequest(SEASON_ID, updated.replace("가을", "겨울")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SEASON_METADATA_REVISION_CONFLICT"))
            .andReturnValid("api-error.v1.schema.json", "시즌 정보 개정 번호 충돌")

        val rebuildTimer = meterRegistry.get("baton.cal.projection.rebuild").timer()
        val rebuildCountBefore = rebuildTimer.count()
        val advancedResponse = update(updated.replace("\"revision\": 2", "\"revision\": 3"))
        assertThat(JsonPath.read<Int>(advancedResponse, "$.revision")).isEqualTo(3)
        assertThat(advancedResponse).isEqualTo(update(updated.replace("가을", "겨울")))
        assertThat(rebuildTimer.count()).isEqualTo(rebuildCountBefore)
        mockMvc.perform(authorizedPost("/internal/api/v1/projections/seasons/{seasonId}/rebuild", SEASON_ID))
            .andExpect(status().isOk)
        mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, requireNotNull(after.getHeader(HttpHeaders.ETAG))))
            .andExpect(header().string(HttpHeaders.LAST_MODIFIED, requireNotNull(after.getHeader(HttpHeaders.LAST_MODIFIED))))
            .andExpect(content().bytes(after.contentAsByteArray))
        mockMvc.perform(
            get("/calendars/v1/{token}.ics", token)
                .header(HttpHeaders.IF_NONE_MATCH, requireNotNull(after.getHeader(HttpHeaders.ETAG))),
        )
            .andExpect(status().isNotModified)
    }

    @Test
    fun `시즌 이름 수신은 개정 번호와 TEXT 검증을 적용한다`() {
        val valid = contractExample("season-calendar-metadata.r0.json")
        listOf(
            valid.replace("\"revision\": 0", "\"revision\": -1"),
            valid.replace("\"revision\": 0", "\"revision\": 0.5"),
            valid.replace("\"revision\": 0", "\"revision\": -0.5"),
            valid.replace("BATON 개발 시즌", ""),
            valid.replace("BATON 개발 시즌", "가".repeat(513)),
            valid.replace("BATON 개발 시즌", "이름\\r주입"),
            valid.replace("BATON 개발 시즌", "가"),
        ).forEach { payload ->
            mockMvc.perform(seasonCalendarMetadataRequest(SEASON_ID, payload))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["123", "12.5", "true"])
    fun `시즌 이름의 숫자와 불리언은 거부하고 같은 내용의 문자열은 처리한다`(value: String) {
        val payload = contractExample("season-calendar-metadata.r0.json")
            .replace("BATON 개발 시즌", value)
        val unquoted = payload.replace("\"displayName\": \"$value\"", "\"displayName\": $value")
        mockMvc.perform(seasonCalendarMetadataRequest(SEASON_ID, unquoted))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andReturnValid("api-error.v1.schema.json", "시즌 이름 타입 오류 응답")
        assertThat(JsonPath.read<String>(update(payload), "$.displayName")).isEqualTo(value)
    }

    private fun update(payload: String): String = mockMvc.updateSeasonCalendarMetadata(SEASON_ID, payload)

    private companion object {
        const val SEASON_ID = "f5316f93-d49e-4230-b1d0-9e9c2d079819"
    }
}
