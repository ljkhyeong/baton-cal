package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import io.baton.cal.support.PostgreSqlTestContainer
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistryAssert
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.server.observation.ServerRequestObservationContext
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
import kotlin.io.encoding.Base64
import kotlin.io.path.readText
import java.nio.file.Path
import java.security.MessageDigest
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

    @Test
    fun `empty season feed preserves canonical bytes validators and conditional responses across rebuild`() {
        val credential = createSubscription()
        val subscriptionId: String = JsonPath.read(credential, "$.subscriptionId")
        val token: String = JsonPath.read(credential, "$.token")
        val golden = emptyFeedGolden()
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

    private fun emptyFeedGolden(): ByteArray = Base64.Mime.decode(
        Path.of("contracts/golden/season-empty.ics.b64").readText(),
    )

    companion object {
        const val INTERNAL_TOKEN = "test-internal-token-that-is-long-enough"
        const val SEASON_ID = "11111111-1111-1111-1111-111111111111"
        const val EPOCH_HTTP_DATE = "Thu, 01 Jan 1970 00:00:00 GMT"
        const val CONTENT_DISPOSITION = "inline; filename=\"baton-calendar.ics\""
        val RESTORED_CREDENTIAL_GENERATION: UUID =
            UUID.fromString("10000000-0000-0000-0000-000000000001")
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestObservationRegistryConfiguration {
    @Bean
    fun testObservationRegistry(): TestObservationRegistry = TestObservationRegistry.create()
}
