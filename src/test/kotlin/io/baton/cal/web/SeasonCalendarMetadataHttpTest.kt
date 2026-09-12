package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import io.baton.cal.calendar.events
import io.baton.cal.calendar.parseIcalendar
import io.baton.cal.calendar.requiredEvent
import io.baton.cal.contract.ContractSchemaSupport
import io.baton.cal.support.PostgreSqlTestContainer
import io.micrometer.core.instrument.MeterRegistry
import net.fortuna.ical4j.model.Property
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.io.path.Path
import kotlin.io.path.readText

@ImportTestcontainers(PostgreSqlTestContainer::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=test-internal-token-that-is-long-enough",
        "baton.cal.previous-internal-token=test-previous-internal-token-that-is-long-enough",
        "baton.cal.public-base-url=https://calendar.example.test",
    ],
)
@Sql("/reset-database.sql")
class SeasonCalendarMetadataHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val meterRegistry: MeterRegistry,
) {
    @Test
    fun `이름 변경은 개정 번호를 따르고 일정 표현과 구독을 유지한다`() {
        val initial = Path("contracts/examples/season-calendar-metadata.r0.json").readText()
        val updated = Path("contracts/examples/season-calendar-metadata.r2.json").readText()
        val initialResponse = update(initial)
        assertThat(JsonPath.read<Int>(initialResponse, "$.revision")).isZero()

        val credential = mockMvc.perform(
            post("/internal/api/v1/subscriptions")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"seasonId":"$SEASON_ID"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val token: String = JsonPath.read(credential, "$.token")
        val emptyFeed = mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray.parseIcalendar()
        assertThat(emptyFeed.events()).isEmpty()
        assertThat(emptyFeed.propertyList.getRequired<Property>("X-WR-CALNAME").value).isEqualTo("BATON 개발 시즌")

        mockMvc.perform(
            post("/internal/api/v1/schedule-snapshots")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(Path("contracts/examples/schedule-snapshot.zoned-active-r0.json").readText()),
        )
            .andExpect(status().isOk)
        val before = mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isOk)
            .andReturn().response
        assertThat(before.contentAsByteArray.parseIcalendar().propertyList.getRequired<Property>("X-WR-CALNAME").value)
            .isEqualTo("BATON 개발 시즌")

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
        assertThat(renamed.propertyList.getRequired<Property>("X-WR-CALNAME").value).isEqualTo("BATON 가을 개발 시즌")
        assertThat(renamed.requiredEvent().toString())
            .isEqualTo(before.contentAsByteArray.parseIcalendar().requiredEvent().toString())

        val conflict = mockMvc.perform(metadataRequest(updated.replace("가을", "겨울")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SEASON_METADATA_REVISION_CONFLICT"))
            .andReturn().response
        ContractSchemaSupport.assertValid("api-error.v1.schema.json", conflict.contentAsString, "시즌 정보 개정 번호 충돌")

        val rebuildTimer = meterRegistry.get("baton.cal.projection.rebuild").timer()
        val rebuildCountBefore = rebuildTimer.count()
        val advancedResponse = update(updated.replace("\"revision\": 2", "\"revision\": 3"))
        assertThat(JsonPath.read<Int>(advancedResponse, "$.revision")).isEqualTo(3)
        assertThat(advancedResponse).isEqualTo(update(updated.replace("가을", "겨울")))
        assertThat(rebuildTimer.count()).isEqualTo(rebuildCountBefore)
        mockMvc.perform(
            post("/internal/api/v1/projections/seasons/{seasonId}/rebuild", SEASON_ID)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION),
        )
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
    fun `시즌 이름 수신은 기존 인증과 UUID 및 TEXT 검증을 적용한다`() {
        val valid = Path("contracts/examples/season-calendar-metadata.r0.json").readText()
        mockMvc.perform(put(PATH, SEASON_ID).contentType(MediaType.APPLICATION_JSON).content(valid))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(metadataRequest(valid, seasonId = "1-1-1-1-1"))
            .andExpect(status().isBadRequest)
        listOf(
            valid.replace("\"revision\": 0", "\"revision\": -1"),
            valid.replace("\"revision\": 0", "\"revision\": 0.5"),
            valid.replace("\"revision\": 0", "\"revision\": -0.5"),
            valid.replace("BATON 개발 시즌", ""),
            valid.replace("BATON 개발 시즌", "가".repeat(513)),
            valid.replace("BATON 개발 시즌", "이름\\r주입"),
            valid.replace("BATON 개발 시즌", "가"),
        ).forEach { payload ->
            mockMvc.perform(metadataRequest(payload))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["123", "12.5", "true"])
    fun `시즌 이름의 숫자와 불리언은 거부하고 같은 내용의 문자열은 처리한다`(value: String) {
        val payload = Path("contracts/examples/season-calendar-metadata.r0.json").readText()
            .replace("BATON 개발 시즌", value)
        val response = mockMvc.perform(
            metadataRequest(payload.replace("\"displayName\": \"$value\"", "\"displayName\": $value")),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andReturn().response.contentAsString
        ContractSchemaSupport.assertValid("api-error.v1.schema.json", response, "시즌 이름 타입 오류 응답")
        assertThat(JsonPath.read<String>(update(payload), "$.displayName")).isEqualTo(value)
    }

    private fun update(payload: String): String {
        val response = mockMvc.perform(metadataRequest(payload))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.seasonId").value(SEASON_ID))
            .andReturn().response.contentAsString
        ContractSchemaSupport.assertValid("season-calendar-metadata-result.v1.schema.json", response, "시즌 이름 수신 응답")
        return response
    }

    private fun metadataRequest(payload: String, seasonId: String = SEASON_ID) = put(PATH, seasonId)
        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload)

    private companion object {
        const val PATH = "/internal/api/v1/seasons/{seasonId}/calendar-metadata"
        const val SEASON_ID = "f5316f93-d49e-4230-b1d0-9e9c2d079819"
        const val AUTHORIZATION = "Bearer test-internal-token-that-is-long-enough"
    }
}
