package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import com.zaxxer.hikari.HikariDataSource
import io.baton.cal.support.PostgreSqlTestContainer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties
import org.springframework.boot.transaction.autoconfigure.TransactionProperties
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import java.nio.file.Path
import java.sql.Connection
import java.time.Duration
import org.hamcrest.Matchers.containsString

@ImportTestcontainers(PostgreSqlTestContainer::class)
@AutoConfigureMockMvc
@Sql("/reset-database.sql")
@SpringBootTest(
    properties = [
        "spring.profiles.active=prod",
        "baton.cal.internal-token=test-internal-token-that-is-long-enough",
        "baton.cal.public-base-url=https://calendar.example.test",
        "baton.cal.subscription-generation=30000000-0000-0000-0000-000000000003",
        "management.server.port=8080",
        "spring.datasource.hikari.connection-timeout=1000",
    ],
)
class OperationalHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tomcatServerProperties: TomcatServerProperties,
    private val jdbcClient: JdbcClient,
    private val transactionProperties: TransactionProperties,
    private val dataSource: HikariDataSource,
) {
    @Test
    fun `연결 풀이 고갈되면 503을 반환하고 연결 반환 후 구독을 정상 처리한다`() {
        val created = mockMvc.perform(
            authorizedPost("/internal/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"seasonId":"$SEASON_ID"}"""),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val token: String = JsonPath.read(created, "$.token")
        val subscriptionId = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        fun createRequest() = put("/internal/api/v1/subscriptions/{subscriptionId}", subscriptionId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $INTERNAL_TOKEN")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"seasonId":"$SEASON_ID"}""")

        val heldConnections = mutableListOf<Connection>()
        try {
            repeat(dataSource.maximumPoolSize) { heldConnections += dataSource.connection }
            for (request in listOf(createRequest(), get("/calendars/v1/{token}.ics", token))) {
                mockMvc.perform(request)
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
                    .andExpect(jsonPath("$.message").value("service is temporarily busy"))
            }
        } finally {
            heldConnections.forEach { it.close() }
        }

        mockMvc.perform(get("/calendars/v1/{token}.ics", token))
            .andExpect(status().isOk)
        mockMvc.perform(createRequest())
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.subscriptionId").value(subscriptionId))
        assertThat(jdbcClient.sql("SELECT count(*) FROM calendar_subscription").query(Int::class.java).single())
            .isEqualTo(2)
    }

    @Test
    fun `데이터베이스 잠금과 실행 및 트랜잭션은 제한 시간 안에서 끝나야 한다`() {
        assertThat(jdbcClient.sql("SHOW lock_timeout").query(String::class.java).single()).isEqualTo("5s")
        assertThat(jdbcClient.sql("SHOW statement_timeout").query(String::class.java).single()).isEqualTo("30s")
        assertThat(transactionProperties.defaultTimeout).isEqualTo(Duration.ofSeconds(30))
    }

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
    fun `actuator는 상세 없는 상태와 Prometheus 메트릭만 공개한다`() {
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

        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("jvm_info")))

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
    fun `JSON 구조 자원 제한을 넘으면 413을 반환한다`() {
        val validSnapshot = Path.of("contracts/examples/schedule-snapshot.utc-active.json").readText()
        val deepValue = "[".repeat(MAX_JSON_NESTING_DEPTH + 1) + "0" + "]".repeat(MAX_JSON_NESTING_DEPTH + 1)
        val deeplyNestedSnapshot = validSnapshot
            .replace("\"time\": {", "\"time\": {\n    \"padding\": $deepValue,")
        val repeatedSummaries = List(MAX_JSON_TOKEN_COUNT / 2 + 1) { index ->
            "\"summary\": \"ROUND $index\","
        }.joinToString("\n")
        val tooManyTokensSnapshot = validSnapshot.replace(
            "\"summary\": \"ROUND 1 운영\",",
            repeatedSummaries,
        )
        val payloads = listOf(
            """{"${"a".repeat(MAX_JSON_NAME_LENGTH + 1)}":true}""",
            deeplyNestedSnapshot,
            """{"revision":${"9".repeat(MAX_JSON_NUMBER_LENGTH + 1)}}""",
            tooManyTokensSnapshot,
        )

        payloads.forEach { payload ->
            mockMvc.perform(
                authorizedPost("/internal/api/v1/schedule-snapshots")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            )
                .andExpect(status().isContentTooLarge)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value("request body exceeds the maximum size"))
        }
    }

    @Test
    fun `작은 잘못된 JSON과 알 수 없는 필드는 400을 반환한다`() {
        listOf(
            """{"seasonId":"""",
            """{"seasonId":"$SEASON_ID","unexpected":true}""",
            """{"seasonId":"AAAAAAAAAAAAAAAAAAAAAA"}""",
            """{"seasonId":"AAAAAAAAAAAAAAAAAAAAAA=="}""",
        ).forEach { payload ->
            mockMvc.perform(
                authorizedPost("/internal/api/v1/subscriptions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            )
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
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
        const val MAX_JSON_NAME_LENGTH = 64
        const val MAX_JSON_NESTING_DEPTH = 16
        const val MAX_JSON_NUMBER_LENGTH = 10
        const val MAX_JSON_TOKEN_COUNT = 8192
    }
}
