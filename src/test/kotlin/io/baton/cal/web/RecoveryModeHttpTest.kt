package io.baton.cal.web

import io.baton.cal.calendar.parseIcalendar
import io.baton.cal.calendar.requiredEvent
import io.baton.cal.calendar.requiredPropertyValue
import io.baton.cal.config.CalProperties
import io.baton.cal.contract.andReturnValid
import io.baton.cal.persistence.CalendarSubscriptionRepository
import io.baton.cal.persistence.CalendarSubscriptionRow
import io.baton.cal.persistence.CalendarSubscriptionStatus
import io.baton.cal.subscription.SubscriptionTokenCodec
import io.baton.cal.support.RecoveryModeInternalHttpTest
import io.baton.cal.support.authorizedGet
import io.baton.cal.support.authorizedPost
import io.baton.cal.support.bearer
import net.fortuna.ical4j.model.Property
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.jdbc.JdbcTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.io.path.Path
import kotlin.io.path.readText

@RecoveryModeInternalHttpTest
class RecoveryModeHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val repository: CalendarSubscriptionRepository,
    private val tokenCodec: SubscriptionTokenCodec,
    private val properties: CalProperties,
    private val jdbcClient: JdbcClient,
) {
    @Test
    fun `복구 중 발급만 차단하고 일정 복구와 구독 폐기를 허용한다`() {
        ingest("schedule-snapshot.zoned-active-r0.json")
        val token = tokenCodec.generate()
        val subscription = CalendarSubscriptionRow(
            id = UUID.randomUUID(),
            seasonId = SEASON_ID,
            tokenHash = tokenCodec.hash(token),
            credentialGeneration = properties.subscriptionGeneration,
            status = CalendarSubscriptionStatus.ACTIVE,
        )
        repository.insert(subscription)

        val expectedError = Path("contracts/examples/api-error.recovery-in-progress.json").readText()
        listOf(
            authorizedPost("/internal/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"seasonId":"${UUID.randomUUID()}"}"""),
            authorizedPost("/internal/api/v1/subscriptions/${subscription.id}/rotate"),
            put("/internal/api/v1/subscriptions/{subscriptionId}", UUID.randomUUID())
                .bearer()
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"seasonId":"${UUID.randomUUID()}"}"""),
        ).forEach { request ->
            mockMvc.perform(request)
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json(expectedError))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andReturnValid("api-error.v1.schema.json", "복구 중 구독 발급 차단 응답")
        }
        assertThat(repository.findById(subscription.id)).isEqualTo(subscription)
        assertThat(JdbcTestUtils.countRowsInTable(jdbcClient, "calendar_subscription")).isEqualTo(1)
        assertThat(JdbcTestUtils.countRowsInTable(jdbcClient, "season_feed_projection")).isEqualTo(1)

        ingest("schedule-snapshot.zoned-active-r2.json")
        ingest("schedule-snapshot.zoned-cancelled.json")
        mockMvc.perform(
            put("/internal/api/v1/seasons/{seasonId}/calendar-metadata", SEASON_ID)
                .bearer()
                .contentType(MediaType.APPLICATION_JSON)
                .content(Path("contracts/examples/season-calendar-metadata.r2.json").readText()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(2))
        mockMvc.perform(authorizedGet("/internal/api/v1/calendar-items/b8ca471a-b228-42fa-8d41-28f05ee90d40"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(3))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
        mockMvc.perform(authorizedGet("/internal/api/v1/subscriptions/{subscriptionId}", subscription.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.generationMatches").value(true))
        mockMvc.perform(authorizedPost("/internal/api/v1/projections/seasons/$SEASON_ID/rebuild"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.itemCount").value(1))

        val feed = mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isOk)
            .andReturn().response
        val event = feed.contentAsByteArray.parseIcalendar().requiredEvent()
        assertThat(event.requiredPropertyValue(Property.SEQUENCE)).isEqualTo("3")
        assertThat(event.requiredPropertyValue(Property.STATUS)).isEqualTo("CANCELLED")
        mockMvc.perform(
            get("/calendars/v1/{token}.ics", token)
                .header(HttpHeaders.IF_NONE_MATCH, requireNotNull(feed.getHeader(HttpHeaders.ETAG))),
        )
            .andExpect(status().isNotModified)

        mockMvc.perform(delete("/internal/api/v1/subscriptions/{subscriptionId}", subscription.id).bearer())
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isNotFound)
    }

    private fun ingest(fileName: String) {
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(Path("contracts/examples", fileName).readText()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("APPLIED"))
    }

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("f5316f93-d49e-4230-b1d0-9e9c2d079819")
    }
}
