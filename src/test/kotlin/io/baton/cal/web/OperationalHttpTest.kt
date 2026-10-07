package io.baton.cal.web

import io.baton.cal.contract.contractExample
import com.jayway.jsonpath.JsonPath
import com.zaxxer.hikari.HikariDataSource
import io.baton.cal.support.CalIntegrationTest
import io.baton.cal.support.SEASON_CALENDAR_METADATA_PATH
import io.baton.cal.support.SNAPSHOT_PATH
import io.baton.cal.support.authorizedPut
import io.baton.cal.support.createSubscription
import io.baton.cal.support.ingestSnapshot
import io.baton.cal.support.jsonContent
import io.baton.cal.support.postSnapshot
import io.baton.cal.support.seasonCalendarMetadataRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties
import org.springframework.boot.transaction.autoconfigure.TransactionProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.jdbc.JdbcTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Connection
import java.time.Duration
import org.hamcrest.Matchers.containsString

@CalIntegrationTest
class OperationalHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val tomcatServerProperties: TomcatServerProperties,
    private val jdbcClient: JdbcClient,
    private val transactionProperties: TransactionProperties,
    private val dataSource: HikariDataSource,
) {
    @Test
    fun `연결 풀이 고갈되면 503을 반환하고 연결 반환 후 구독을 정상 처리한다`() {
        val token: String = JsonPath.read(mockMvc.createSubscription(SEASON_ID), "$.token")
        val subscriptionId = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        fun createRequest() = authorizedPut("/internal/api/v1/subscriptions/{subscriptionId}", subscriptionId)
            .jsonContent("""{"seasonId":"$SEASON_ID"}""")

        val heldConnections = mutableListOf<Connection>()
        try {
            repeat(dataSource.maximumPoolSize) { heldConnections += dataSource.connection }
            for (request in listOf(createRequest(), get("/calendars/v1/{token}.ics", token))) {
                mockMvc.perform(request)
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
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
        assertThat(JdbcTestUtils.countRowsInTable(jdbcClient, "calendar_subscription")).isEqualTo(2)
    }

    @Test
    fun `데이터베이스 잠금과 실행 및 트랜잭션은 제한 시간 안에서 끝나야 한다`() {
        assertThat(jdbcClient.sql("SHOW lock_timeout").query(String::class.java).single()).isEqualTo("5s")
        assertThat(jdbcClient.sql("SHOW statement_timeout").query(String::class.java).single()).isEqualTo("30s")
        assertThat(transactionProperties.defaultTimeout).isEqualTo(Duration.ofSeconds(30))
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
    fun `알림 규칙이 쓰는 내부 인증 실패와 수신 거부 지표를 노출한다`() {
        mockMvc.perform(post(SNAPSHOT_PATH).jsonContent("{}"))
            .andExpect(status().isUnauthorized)
        mockMvc.postSnapshot("{}")
            .andExpect(status().isBadRequest)
        mockMvc.perform(seasonCalendarMetadataRequest(SEASON_ID, "{}"))
            .andExpect(status().isBadRequest)

        // operations/prometheus/alerts.yml의 CalInternalAuthenticationFailed·CalIngestionRejected가 쓰는 지표다.
        val metrics = mockMvc.perform(get("/actuator/prometheus")).andReturn().response.contentAsString.lines()
        assertThat(metrics).anySatisfy {
            assertThat(it).startsWith("baton_cal_internal_authentication_total{").contains("result=\"unauthorized\"")
        }
        for (uri in listOf(SNAPSHOT_PATH, SEASON_CALENDAR_METADATA_PATH)) {
            assertThat(metrics).anySatisfy {
                assertThat(it).startsWith("http_server_requests_seconds_count{")
                    .contains("method=\"", "status=\"400\"", "uri=\"$uri\"")
            }
        }
    }

    @Test
    fun `JSON 문서 상한 경계는 허용하고 한 바이트 초과는 413을 반환한다`() {
        mockMvc.ingestSnapshot(snapshotDocumentOfSize(MAX_JSON_DOCUMENT_LENGTH))

        mockMvc.postSnapshot(snapshotDocumentOfSize(MAX_JSON_DOCUMENT_LENGTH + 1))
            .andExpect(status().isContentTooLarge)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"))
            .andExpect(jsonPath("$.message").value("request body exceeds the maximum size"))
    }

    @Test
    fun `JSON 구조 자원 제한을 넘으면 413을 반환한다`() {
        val validSnapshot = contractExample("schedule-snapshot.utc-active.json")
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
            mockMvc.postSnapshot(payload)
                .andExpect(status().isContentTooLarge)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value("request body exceeds the maximum size"))
        }
    }

    @Test
    fun `접근 로그 기본값은 비밀 URL을 기록하지 않는다`() {
        val accesslog = tomcatServerProperties.accesslog

        assertThat(accesslog.isEnabled).isFalse()
        assertThat(accesslog.pattern)
            .doesNotContain("%r", "%U", "%q")
    }

    // 예시 앞에 ASCII 공백을 붙여 UTF-8 바이트 길이를 맞춘다.
    private fun snapshotDocumentOfSize(size: Int): String {
        val snapshot = contractExample("schedule-snapshot.utc-active.json")
        return " ".repeat(size - snapshot.encodeToByteArray().size) + snapshot
    }

    companion object {
        const val SEASON_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val MAX_JSON_DOCUMENT_LENGTH = 131_072
        const val MAX_JSON_NAME_LENGTH = 64
        const val MAX_JSON_NESTING_DEPTH = 16
        const val MAX_JSON_NUMBER_LENGTH = 10
        const val MAX_JSON_TOKEN_COUNT = 8192
    }
}
