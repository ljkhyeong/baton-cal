package io.baton.cal.web

import io.baton.cal.calendar.goldenIcalendarFixture
import io.baton.cal.persistence.CalendarSubscriptionRepository
import io.baton.cal.persistence.SeasonFeedProjectionRow
import io.baton.cal.support.CalIntegrationTest
import io.baton.cal.support.authorizedDelete
import io.baton.cal.support.authorizedPost
import io.baton.cal.support.createSubscription
import io.baton.cal.support.eqArg
import io.baton.cal.support.rotateSubscription
import com.jayway.jsonpath.JsonPath
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistryAssert
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@TestPropertySource(properties = ["baton.cal.subscription-generation=20000000-0000-0000-0000-000000000002"])
@CalIntegrationTest
class PublicCalendarContractTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbcClient: JdbcClient,
    private val observationRegistry: TestObservationRegistry,
    private val subscriptionRepository: CalendarSubscriptionRepository,
) {
    @Test
    fun `empty season feed preserves canonical bytes validators and conditional responses across rebuild`() {
        val token = createToken()
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
        val validators = listOf(
            HttpHeaders.IF_NONE_MATCH to expectedEtag,
            HttpHeaders.IF_NONE_MATCH to "W/$expectedEtag",
            HttpHeaders.IF_MODIFIED_SINCE to EPOCH_HTTP_DATE,
        )
        for ((name, value) in validators) {
            assertNotModified(get("/calendars/v1/{token}.ics", token).header(name, value), expectedEtag, EPOCH_HTTP_DATE)
        }
        verifyBodyNotRead(metadataReads = 3)

        mockMvc.perform(authorizedPost("/internal/api/v1/projections/seasons/{seasonId}/rebuild", SEASON_ID))
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
    fun `HEAD는 본문 조회 없이 GET과 같은 헤더와 UTF-8 바이트 크기를 반환한다`() {
        val token = createToken()
        val golden = goldenIcalendarFixture("season-unicode-fold-boundaries.ics.b64")
        val etag = "\"${MessageDigest.getInstance("SHA-256").digest(golden).toHexString()}\""
        jdbcClient.sql(
            "UPDATE season_feed_projection SET representation = :bytes, etag = :etag WHERE season_id = :seasonId",
        )
            .param("bytes", golden)
            .param("etag", etag)
            .param("seasonId", UUID.fromString(SEASON_ID))
            .update()
        val getResponse = mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isOk)
            .andExpect(content().bytes(golden))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, golden.size.toLong()))
            .andReturn().response
        clearInvocations(subscriptionRepository)

        for (request in listOf(
            head("/calendars/v1/{token}.ics", token),
            head("/calendars/v1/{token}.ics", token)
                .header(HttpHeaders.IF_NONE_MATCH, "\"old-etag\"")
                .header(HttpHeaders.IF_MODIFIED_SINCE, FIXED_NOW_HTTP_DATE),
        )) {
            val result = mockMvc.perform(request)
                .andExpect(status().isOk)
                .andExpect(content().bytes(byteArrayOf()))
            for (name in listOf(
                HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_LENGTH,
                HttpHeaders.ETAG, HttpHeaders.LAST_MODIFIED, HttpHeaders.CACHE_CONTROL,
            )) {
                result.andExpect(header().string(name, getResponse.getHeader(name)!!))
            }
        }
        verifyBodyNotRead(metadataReads = 2)
    }

    @Test
    fun `HEAD 조건부 조회는 빈 피드의 Unix epoch와 304를 유지한다`() {
        val token = createToken()
        val etag = mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andReturn().response.getHeader(HttpHeaders.ETAG)!!
        clearInvocations(subscriptionRepository)

        for ((name, value) in listOf(HttpHeaders.IF_NONE_MATCH to etag, HttpHeaders.IF_MODIFIED_SINCE to EPOCH_HTTP_DATE)) {
            assertNotModified(head("/calendars/v1/{token}.ics", token).header(name, value), etag, EPOCH_HTTP_DATE)
        }
        verify(subscriptionRepository, never()).findProjectionByActiveTokenHash(
            ArgumentMatchers.anyString(), eqArg(CREDENTIAL_GENERATION),
        )
    }

    @Test
    fun `메타데이터 조회 뒤 바뀐 투영의 검증 값으로 조건부 요청을 다시 판정한다`() {
        val token = createToken()
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
            get("/calendars/v1/{token}.ics", token).header(HttpHeaders.IF_NONE_MATCH, updatedEtag),
            updatedEtag,
            ONE_SECOND_AFTER_EPOCH_HTTP_DATE,
        )
    }

    @Test
    fun `미래 Last-Modified는 공개 응답 시각을 넘지 않는다`() {
        val token = createToken()
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
        val token = createToken()
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

        val credential = mockMvc.createSubscription(SEASON_ID)
        val subscriptionId: String = JsonPath.read(credential, "$.subscriptionId")
        val originalToken: String = JsonPath.read(credential, "$.token")

        val replacementToken: String = JsonPath.read(mockMvc.rotateSubscription(subscriptionId), "$.token")
        assertPublicNotFound(originalToken)

        mockMvc.perform(authorizedDelete("/internal/api/v1/subscriptions/{subscriptionId}", subscriptionId))
            .andExpect(status().isNoContent)

        assertPublicNotFound(replacementToken)
    }

    @Test
    fun `복원된 이전 세대 토큰은 현재 세대로 회전하기 전까지 공개되지 않는다`() {
        val credential = mockMvc.createSubscription(SEASON_ID)
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

        val currentToken: String = JsonPath.read(mockMvc.rotateSubscription(subscriptionId), "$.token")

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
                    .hasSize(4)
                    .containsOnly("/calendars/v1/{token}.ics")
                assertThat(observedUrls).anySatisfy { url ->
                    assertThat(url).endsWith("/actuator/health")
                }
            }
    }

    private fun createToken(): String = JsonPath.read(mockMvc.createSubscription(SEASON_ID), "$.token")

    private fun verifyBodyNotRead(metadataReads: Int) {
        verify(subscriptionRepository, times(metadataReads))
            .findProjectionHeadersByActiveTokenHash(ArgumentMatchers.anyString(), eqArg(CREDENTIAL_GENERATION))
        verify(subscriptionRepository, never())
            .findProjectionByActiveTokenHash(ArgumentMatchers.anyString(), eqArg(CREDENTIAL_GENERATION))
    }

    private fun assertNotModified(request: MockHttpServletRequestBuilder, etag: String, lastModified: String) {
        mockMvc.perform(request)
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
        for (request in listOf(get(path), head(path))) {
            mockMvc.perform(request)
                .andExpect(status().isNotFound)
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE))
                .andExpect(content().bytes(byteArrayOf()))
        }
    }

    companion object {
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

    // 관측 기록 확인용 레지스트리와 Last-Modified 상한 검증용 고정 시계로 바꾼다. 중첩 클래스라 이 테스트에만 적용된다.
    @TestConfiguration(proxyBeanMethods = false)
    class PublicCalendarTestConfiguration {
        @Bean
        fun testObservationRegistry(): TestObservationRegistry = TestObservationRegistry.create()

        @Bean
        @Primary
        fun testClock(): Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
    }
}
