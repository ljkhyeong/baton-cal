package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import io.baton.cal.calendar.goldenIcalendarFixture
import io.baton.cal.persistence.CalendarSubscriptionRepository
import io.baton.cal.persistence.SeasonFeedProjectionRow
import io.baton.cal.support.PostgreSqlTestContainer
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistryAssert
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@ImportTestcontainers(PostgreSqlTestContainer::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=test-internal-token-that-is-long-enough",
        "baton.cal.public-base-url=https://calendar.example.test",
        "baton.cal.subscription-generation=20000000-0000-0000-0000-000000000002",
    ],
)
@Import(TestObservationRegistryConfiguration::class)
@Sql("/reset-database.sql")
class PublicCalendarContractTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbcClient: JdbcClient,
    private val observationRegistry: TestObservationRegistry,
) {
    @MockitoSpyBean
    lateinit var subscriptionRepository: CalendarSubscriptionRepository

    @Test
    fun `empty season feed preserves canonical bytes validators and conditional responses across rebuild`() {
        val credential = createSubscription()
        val token: String = JsonPath.read(credential, "$.token")
        val golden = goldenIcalendarFixture("season-empty.ics.b64")
        val expectedEtag = "\"${MessageDigest.getInstance("SHA-256").digest(golden).toHexString()}\""

        mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/calendar;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, CONTENT_DISPOSITION))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
            .andExpect(header().string(HttpHeaders.ETAG, expectedEtag))
            .andExpect(header().string(HttpHeaders.LAST_MODIFIED, EPOCH_HTTP_DATE))
            .andExpect(content().bytes(golden))

        clearInvocations(subscriptionRepository)
        assertNotModified(
            token = token,
            headerName = HttpHeaders.IF_NONE_MATCH,
            headerValue = expectedEtag,
            etag = expectedEtag,
            lastModified = EPOCH_HTTP_DATE,
        )
        assertNotModified(
            token = token,
            headerName = HttpHeaders.IF_MODIFIED_SINCE,
            headerValue = EPOCH_HTTP_DATE,
            etag = expectedEtag,
            lastModified = EPOCH_HTTP_DATE,
        )
        verify(subscriptionRepository, times(2)).findProjectionMetadataByActiveTokenHash(
            ArgumentMatchers.anyString(),
            eqArg(CREDENTIAL_GENERATION),
        )
        verify(subscriptionRepository, never()).findProjectionByActiveTokenHash(
            ArgumentMatchers.anyString(),
            eqArg(CREDENTIAL_GENERATION),
        )

        mockMvc.perform(
            authorizedPost("/internal/api/v1/projections/seasons/{seasonId}/rebuild", SEASON_ID),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.seasonId").value(SEASON_ID))
            .andExpect(jsonPath("$.etag").value(expectedEtag))
            .andExpect(jsonPath("$.itemCount").value(0))

        mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, expectedEtag))
            .andExpect(header().string(HttpHeaders.LAST_MODIFIED, EPOCH_HTTP_DATE))
            .andExpect(content().bytes(golden))
    }

    @Test
    fun `메타데이터 조회 뒤 바뀐 투영의 검증 값으로 조건부 요청을 다시 판정한다`() {
        val credential = createSubscription()
        val token: String = JsonPath.read(credential, "$.token")
        val updatedEtag = "\"updated-etag\""
        val updatedLastModified = Instant.ofEpochSecond(1)
        doReturn(
            SeasonFeedProjectionRow(
                seasonId = UUID.fromString(SEASON_ID),
                representation = byteArrayOf(1),
                etag = updatedEtag,
                lastModified = updatedLastModified,
            ),
        ).`when`(subscriptionRepository).findProjectionByActiveTokenHash(
            ArgumentMatchers.anyString(),
            eqArg(CREDENTIAL_GENERATION),
        )

        assertNotModified(
            token = token,
            headerName = HttpHeaders.IF_NONE_MATCH,
            headerValue = updatedEtag,
            etag = updatedEtag,
            lastModified = ONE_SECOND_AFTER_EPOCH_HTTP_DATE,
        )
    }

    @Test
    fun `미래 Last-Modified는 공개 응답 시각을 넘지 않는다`() {
        val credential = createSubscription()
        val token: String = JsonPath.read(credential, "$.token")
        jdbcClient.sql(
            """
            UPDATE season_feed_projection
            SET last_modified = :futureLastModified
            WHERE season_id = :seasonId
            """.trimIndent(),
        )
            .param("futureLastModified", FIXED_NOW.plusSeconds(30).atOffset(ZoneOffset.UTC))
            .param("seasonId", UUID.fromString(SEASON_ID))
            .update()

        mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.LAST_MODIFIED, FIXED_NOW_HTTP_DATE))
    }

    @Test
    fun `메타데이터 조회 뒤 자격 증명이 무효화되면 본문 없는 404를 반환한다`() {
        val credential = createSubscription()
        val token: String = JsonPath.read(credential, "$.token")
        doReturn(null).`when`(subscriptionRepository).findProjectionByActiveTokenHash(
            ArgumentMatchers.anyString(),
            eqArg(CREDENTIAL_GENERATION),
        )

        mockMvc.perform(
            get("/calendars/v1/{token}.ics", token)
                .header(HttpHeaders.IF_NONE_MATCH, "\"stale-etag\""),
        )
            .andExpect(status().isNotFound)
            .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
            .andExpect(content().bytes(byteArrayOf()))
    }

    @Test
    fun `unknown malformed rotated and revoked tokens return the same bodyless not found response`() {
        assertPublicNotFound("unknown-token-that-is-not-a-real-credential")
        assertPublicNotFound("short!token")
        assertPublicPathNotFound("/calendars/v1/.ics")
        assertPublicPathNotFound("/calendars/v1/nested/token.ics")

        val credential = createSubscription()
        val subscriptionId: String = JsonPath.read(credential, "$.subscriptionId")
        val originalToken: String = JsonPath.read(credential, "$.token")

        val rotated = mockMvc.perform(
            authorizedPost("/internal/api/v1/subscriptions/{subscriptionId}/rotate", subscriptionId),
        )
            .andExpect(status().isOk)
            .andReturn()
        val replacementToken: String = JsonPath.read(rotated.response.contentAsString, "$.token")

        assertPublicNotFound(originalToken)

        mockMvc.perform(
            delete("/internal/api/v1/subscriptions/{subscriptionId}", subscriptionId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $INTERNAL_TOKEN"),
        )
            .andExpect(status().isNoContent)

        assertPublicNotFound(replacementToken)
    }

    @Test
    fun `복원된 이전 세대 토큰은 현재 세대로 회전하기 전까지 공개되지 않는다`() {
        val credential = createSubscription()
        val subscriptionId: String = JsonPath.read(credential, "$.subscriptionId")
        val restoredToken: String = JsonPath.read(credential, "$.token")

        jdbcClient.sql(
            """
            UPDATE calendar_subscription
            SET credential_generation = :restoredGeneration
            WHERE id = :subscriptionId
            """.trimIndent(),
        )
            .param("restoredGeneration", RESTORED_CREDENTIAL_GENERATION)
            .param("subscriptionId", UUID.fromString(subscriptionId))
            .update()

        assertPublicNotFound(restoredToken)

        val rotated = mockMvc.perform(
            authorizedPost("/internal/api/v1/subscriptions/{subscriptionId}/rotate", subscriptionId),
        )
            .andExpect(status().isOk)
            .andReturn()
        val currentToken: String = JsonPath.read(rotated.response.contentAsString, "$.token")

        assertPublicNotFound(restoredToken)
        mockMvc.perform(get("/calendars/v1/{token}.ics", currentToken))
            .andExpect(status().isOk)
    }

    @Test
    fun `공개 캘린더 관측 URL은 실제 토큰을 기록하지 않는다`() {
        val sensitiveToken = "sensitive-calendar-token-that-must-not-appear"
        observationRegistry.clear()

        assertPublicNotFound(sensitiveToken)
        assertPublicPathNotFound("/calendars/v1/nested/$sensitiveToken.ics")
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)

        TestObservationRegistryAssert.assertThat(observationRegistry)
            .hasHandledContextsThatSatisfy { contexts ->
                val observedUrls = contexts
                    .filterIsInstance<ServerRequestObservationContext>()
                    .mapNotNull { it.getHighCardinalityKeyValue("http.url")?.value }
                val publicCalendarUrls = observedUrls.filter { it.startsWith("/calendars/v1/") }
                assertThat(publicCalendarUrls)
                    .hasSize(2)
                    .containsOnly("/calendars/v1/{token}.ics")
                assertThat(observedUrls).anySatisfy { url ->
                    assertThat(url).endsWith("/actuator/health")
                }
            }
    }

    private fun createSubscription(): String = mockMvc.perform(
        authorizedPost("/internal/api/v1/subscriptions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"seasonId":"$SEASON_ID"}"""),
    )
        .andExpect(status().isCreated)
        .andReturn()
        .response
        .contentAsString

    private fun assertNotModified(
        token: String,
        headerName: String,
        headerValue: String,
        etag: String,
        lastModified: String,
    ) {
        mockMvc.perform(
            get("/calendars/v1/{token}.ics", token)
                .header(headerName, headerValue),
        )
            .andExpect(status().isNotModified)
            .andExpect(header().string(HttpHeaders.ETAG, etag))
            .andExpect(header().string(HttpHeaders.LAST_MODIFIED, lastModified))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
            .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
            .andExpect(content().bytes(byteArrayOf()))
    }

    private fun assertPublicNotFound(token: String) {
        assertPublicPathNotFound("/calendars/v1/$token.ics")
    }

    private fun assertPublicPathNotFound(path: String) {
        mockMvc.perform(get(path))
            .andExpect(status().isNotFound)
            .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
            .andExpect(content().bytes(byteArrayOf()))
    }

    private fun authorizedPost(path: String, vararg uriVariables: Any) =
        post(path, *uriVariables).header(HttpHeaders.AUTHORIZATION, "Bearer $INTERNAL_TOKEN")

    private fun <T> eqArg(value: T): T = ArgumentMatchers.eq(value) ?: value

    companion object {
        const val INTERNAL_TOKEN = "test-internal-token-that-is-long-enough"
        const val SEASON_ID = "11111111-1111-1111-1111-111111111111"
        const val EPOCH_HTTP_DATE = "Thu, 01 Jan 1970 00:00:00 GMT"
        const val ONE_SECOND_AFTER_EPOCH_HTTP_DATE = "Thu, 01 Jan 1970 00:00:01 GMT"
        const val FIXED_NOW_HTTP_DATE = "Sat, 29 Aug 2026 10:00:00 GMT"
        const val CONTENT_DISPOSITION = "inline; filename=\"baton-calendar.ics\""
        val FIXED_NOW: Instant = Instant.parse("2026-08-29T10:00:00Z")
        val RESTORED_CREDENTIAL_GENERATION: UUID =
            UUID.fromString("10000000-0000-0000-0000-000000000001")
        val CREDENTIAL_GENERATION: UUID =
            UUID.fromString("20000000-0000-0000-0000-000000000002")
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestObservationRegistryConfiguration {
    @Bean
    fun testObservationRegistry(): TestObservationRegistry = TestObservationRegistry.create()

    @Bean
    @Primary
    fun testClock(): Clock = Clock.fixed(PublicCalendarContractTest.FIXED_NOW, ZoneOffset.UTC)
}
