package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import io.baton.cal.support.PostgreSqlTestContainer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import java.nio.file.Path

@ImportTestcontainers(PostgreSqlTestContainer::class)
@AutoConfigureMockMvc
@Sql("/reset-database.sql")
@SpringBootTest(
    properties = [
        "spring.profiles.active=prod",
        "baton.cal.internal-token=test-internal-token-that-is-long-enough",
        "baton.cal.public-base-url=https://calendar.example.test",
        "baton.cal.subscription-generation=30000000-0000-0000-0000-000000000003",
    ],
)
class OperationalHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tomcatServerProperties: TomcatServerProperties,
) {
    @Test
    fun `one-time subscription credentials cannot be stored by clients`() {
        val created = mockMvc.perform(
            authorizedPost("/internal/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"seasonId":"$SEASON_ID"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn()

        val subscriptionId: String = JsonPath.read(created.response.contentAsString, "$.subscriptionId")

        mockMvc.perform(
            authorizedPost("/internal/api/v1/subscriptions/{subscriptionId}/rotate", subscriptionId),
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
    }

    @Test
    fun `actuator exposes only detail-free health and its probes`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist())
            .andExpect(jsonPath("$.details").doesNotExist())

        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))

        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))

        mockMvc.perform(get("/actuator/info"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `JSON 문서 상한 경계는 허용하고 한 바이트 초과는 413을 반환한다`() {
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(snapshotDocumentOfSize(MAX_JSON_DOCUMENT_LENGTH)),
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(snapshotDocumentOfSize(MAX_JSON_DOCUMENT_LENGTH + 1)),
        )
            .andExpect(status().isContentTooLarge)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"))
            .andExpect(jsonPath("$.message").value("request body exceeds the maximum size"))
    }

    @Test
    fun `문서 상한 안의 잘못된 숫자는 크기 초과가 아닌 잘못된 요청으로 분류한다`() {
        val validSnapshot = Path.of("contracts/examples/schedule-snapshot.utc-active.json").readText()
        val invalidSnapshot = validSnapshot.replace(
            "\"revision\": 0",
            "\"revision\": ${"9".repeat(1_001)}",
        )

        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidSnapshot),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `접근 로그 기본값은 비밀 URL을 기록하지 않는다`() {
        val accesslog = tomcatServerProperties.accesslog

        assertThat(accesslog.isEnabled).isFalse()
        assertThat(accesslog.pattern)
            .doesNotContain("%r", "%U", "%q")
    }

    private fun authorizedPost(path: String, vararg uriVariables: Any) =
        post(path, *uriVariables).header(HttpHeaders.AUTHORIZATION, "Bearer $INTERNAL_TOKEN")

    private fun snapshotDocumentOfSize(size: Int): ByteArray {
        val snapshot = Path.of("contracts/examples/schedule-snapshot.utc-active.json").readBytes()
        return ByteArray(size - snapshot.size) { ' '.code.toByte() } + snapshot
    }

    companion object {
        const val INTERNAL_TOKEN = "test-internal-token-that-is-long-enough"
        const val SEASON_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val MAX_JSON_DOCUMENT_LENGTH = 131_072
    }
}
