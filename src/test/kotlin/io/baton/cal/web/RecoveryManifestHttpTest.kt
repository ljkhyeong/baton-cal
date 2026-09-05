package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import io.baton.cal.contract.ContractSchemaSupport
import io.baton.cal.persistence.RecoveryManifestRepository
import io.baton.cal.recovery.RecoveryManifestDigest
import io.baton.cal.support.PostgreSqlTestContainer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.readText

@ImportTestcontainers(PostgreSqlTestContainer::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=recovery-manifest-test-token-that-is-long-enough",
        "baton.cal.recovery-mode=true",
    ],
)
@Sql("/reset-database.sql")
class RecoveryManifestHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val repository: RecoveryManifestRepository,
    private val jdbcClient: JdbcClient,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @Test
    fun `복구 상태 조회는 진행과 완료를 구분하고 이후 원본 변경에도 완료 기록을 유지한다`() {
        ingest("schedule-snapshot.zoned-active-r0.json")
        val state = repository.currentSeasonState(SEASON_ID)
        val verified = verifySeason(state.itemCount, state.itemDigest, null, null)
        val before = repository.listSeasonManifests(UUID.fromString(RECOVERY_ID))

        readRunStatus("IN_PROGRESS", 1)
        val completed = complete(completionPayload(listOf(state)))
        val completedAt: String = JsonPath.read(completed, "$.completedAt")
        ingest("schedule-snapshot.zoned-cancelled.json")
        assertThat(verifySeason(state.itemCount, state.itemDigest, null, null)).isEqualTo(verified)
        mockMvc.perform(
            put("/internal/api/v1/recovery-runs/{recoveryId}/seasons/{seasonId}/manifest", RECOVERY_ID, SEASON_ID)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(Path("contracts/examples/recovery-season-manifest.json").readText()),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RECOVERY_RUN_CONFLICT"))
        val status = readRunStatus("COMPLETED", 1)

        assertThat(JsonPath.read<String>(status, "$.completedAt")).isEqualTo(completedAt)
        assertThat(repository.listSeasonManifests(UUID.fromString(RECOVERY_ID))).isEqualTo(before)
    }

    @Test
    fun `빈 데이터의 완료 기록도 진행 상태 조회에서 찾는다`() {
        complete(completionPayload(emptyList()))
        readRunStatus("COMPLETED", 0)
    }

    @Test
    fun `시즌 진단은 복구 실행이 없어도 일정과 이름 불일치를 나누어 확인한다`() {
        ingest("schedule-snapshot.zoned-cancelled.json")
        val initial = readSeasonState()
        assertThat(JsonPath.read<Any?>(initial, "$.metadataRevision")).isNull()
        assertThat(JsonPath.read<Any?>(initial, "$.metadataDigest")).isNull()
        updateMetadata()
        val named = readSeasonState()
        assertThat(JsonPath.read<String>(named, "$.itemDigest"))
            .isEqualTo(JsonPath.read<String>(initial, "$.itemDigest"))
        assertThat(JsonPath.read<Int>(named, "$.metadataRevision")).isEqualTo(2)
        assertThat(JsonPath.read<String>(named, "$.metadataDigest")).hasSize(64)
        assertThat(jdbcClient.sql("SELECT count(*) FROM recovery_season_manifest").query(Int::class.java).single())
            .isZero()
        assertThat(jdbcClient.sql("SELECT count(*) FROM recovery_run_completion").query(Int::class.java).single())
            .isZero()
    }

    @Test
    fun `복구 진단은 인증과 UUID를 확인하고 없는 수신 기록은 404다`() {
        listOf(
            "/internal/api/v1/recovery-runs/$RECOVERY_ID",
            "/internal/api/v1/seasons/$SEASON_ID/recovery-state",
        ).forEach { path ->
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized)
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isNotFound)
            mockMvc.perform(
                get(path.replace(RECOVERY_ID, "invalid").replace(SEASON_ID.toString(), "invalid"))
                    .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION),
            ).andExpect(status().isBadRequest)
        }
        updateMetadata()
        val metadataOnly = readSeasonState()
        assertThat(JsonPath.read<Int>(metadataOnly, "$.itemCount")).isZero()
    }

    @Test
    fun `복원 스모크의 고정 매니페스트는 최신 취소와 시즌 이름을 모두 요구한다`() {
        val manifest = Path("contracts/examples/recovery-season-manifest.zoned-cancelled.json").readText()
        val completion = Path("contracts/examples/recovery-run-completion.zoned-cancelled.json").readText()
        fun manifestRequest() = put(
            "/internal/api/v1/recovery-runs/{recoveryId}/seasons/{seasonId}/manifest", RECOVERY_ID, SEASON_ID,
        )
            .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
            .contentType(MediaType.APPLICATION_JSON)
            .content(manifest)

        ingest("schedule-snapshot.zoned-active-r2.json")
        updateMetadata()
        mockMvc.perform(manifestRequest()).andExpect(status().isConflict)
        mockMvc.perform(completionRequest(completion)).andExpect(status().isConflict)
        ingest("schedule-snapshot.zoned-cancelled.json")
        mockMvc.perform(manifestRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("VERIFIED"))
        val first = complete(completion)
        assertThat(complete(completion)).isEqualTo(first)
    }

    @Test
    fun `전체 시즌 상태가 일치하면 복구 완료 신호를 멱등하게 반환한다`() {
        ingest("schedule-snapshot.zoned-active-r0.json")
        updateMetadata()
        val state = repository.currentSeasonState(SEASON_ID)

        val manifestResponse = verifySeason(state.itemCount, state.itemDigest, state.metadataRevision, state.metadataDigest)
        ContractSchemaSupport.assertValid(
            "recovery-season-manifest-result.v1.schema.json",
            manifestResponse,
            "시즌 복구 매니페스트 검증 응답",
        )
        val completionPayload = completionPayload(listOf(state))
        val first = complete(completionPayload)
        val duplicate = complete(completionPayload)

        assertThat(duplicate).isEqualTo(first)
        ContractSchemaSupport.assertValid(
            "recovery-run-completion-result.v1.schema.json",
            first,
            "전체 복구 완료 응답",
        )
        mockMvc.perform(completionRequest(completionPayload(emptyList())))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RECOVERY_RUN_CONFLICT"))
        assertThat(complete(completionPayload)).isEqualTo(first)
    }

    @ParameterizedTest
    @ValueSource(strings = ["run", "state"])
    fun `복구 잠금 대기가 끝나면 503과 재시도 간격을 반환한다`(lock: String) {
        val payload = completionPayload(emptyList())
        Executors.newSingleThreadExecutor().use { executor ->
            transaction.executeWithoutResult {
                if (lock == "run") {
                    repository.lockRecoveryRun(UUID.fromString(RECOVERY_ID))
                } else {
                    jdbcClient.sql("LOCK TABLE calendar_item IN ROW EXCLUSIVE MODE").update()
                }
                executor.submit {
                    transaction.executeWithoutResult { rollback ->
                        rollback.setRollbackOnly()
                        jdbcClient.sql("SET LOCAL lock_timeout TO '200ms'").update()
                        val response = mockMvc.perform(completionRequest(payload))
                            .andExpect(status().isServiceUnavailable)
                            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                            .andExpect(content().json(Path("contracts/examples/api-error.service-busy.json").readText()))
                            .andReturn().response.contentAsString
                        ContractSchemaSupport.assertValid("api-error.v1.schema.json", response, "복구 잠금 시간 초과")
                    }
                }.get(5, TimeUnit.SECONDS)
            }
        }
        assertThat(repository.findCompletion(UUID.fromString(RECOVERY_ID))).isNull()
        complete(payload)
    }

    @Test
    fun `동시 완료 요청은 먼저 저장한 완료 시각으로 응답한다`() {
        val payload = completionPayload(emptyList())
        Executors.newSingleThreadExecutor().use { executor ->
            lateinit var duplicate: Future<String>
            val first = transaction.execute {
                val response = complete(payload)
                val blocker = jdbcClient.sql("SELECT pg_backend_pid()").query(Int::class.java).single()
                duplicate = executor.submit<String> { complete(payload) }
                await().atMost(3, TimeUnit.SECONDS).untilAsserted {
                    assertThat(
                        jdbcClient.sql(
                            """
                            SELECT count(*) FROM pg_locks
                            WHERE locktype = 'advisory' AND NOT granted
                              AND :blocker = ANY(pg_blocking_pids(pid))
                            """.trimIndent(),
                        ).param("blocker", blocker).query(Int::class.java).single(),
                    ).isEqualTo(1)
                }
                response
            }
            assertThat(duplicate.get(5, TimeUnit.SECONDS)).isEqualTo(first)
        }
    }

    @Test
    fun `시즌 상태가 바뀌면 최신 매니페스트를 다시 검증하기 전까지 완료하지 않는다`() {
        ingest("schedule-snapshot.zoned-active-r0.json")
        val initial = repository.currentSeasonState(SEASON_ID)
        verifySeason(initial.itemCount, initial.itemDigest, null, null)

        ingest("schedule-snapshot.zoned-active-r2.json")
        mockMvc.perform(completionRequest(completionPayload(listOf(initial))))
            .andExpect(status().isConflict)
            .andExpect(content().json(Path("contracts/examples/api-error.recovery-manifest-mismatch.json").readText()))

        val current = repository.currentSeasonState(SEASON_ID)
        verifySeason(current.itemCount, current.itemDigest, null, null)
        complete(completionPayload(listOf(current)))
    }

    @Test
    fun `현재 데이터에 없는 빈 시즌을 매니페스트에 추가하면 완료하지 않는다`() {
        val extraSeasonId = UUID.fromString("ea9696eb-4a62-4f37-a11d-9cb040b0fd03")
        val extra = repository.currentSeasonState(extraSeasonId)
        verifySeason(
            extra.itemCount,
            extra.itemDigest,
            extra.metadataRevision,
            extra.metadataDigest,
            extraSeasonId,
        )

        mockMvc.perform(completionRequest(completionPayload(listOf(extra))))
            .andExpect(status().isConflict)
            .andExpect(content().json(Path("contracts/examples/api-error.recovery-manifest-mismatch.json").readText()))
    }

    private fun ingest(fileName: String) {
        mockMvc.perform(
            post("/internal/api/v1/schedule-snapshots")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(Path("contracts/examples", fileName).readText()),
        ).andExpect(status().isOk)
    }

    private fun readRunStatus(expectedStatus: String, seasonCount: Int): String = mockMvc.perform(
        get("/internal/api/v1/recovery-runs/{recoveryId}", RECOVERY_ID)
            .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION),
    )
        .andExpect(status().isOk)
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.status").value(expectedStatus))
        .andExpect(jsonPath("$.verifiedSeasonCount").value(seasonCount))
        .andExpect(jsonPath("$.recoveryMode").value(true))
        .andReturn().response.contentAsString.also {
            ContractSchemaSupport.assertValid("recovery-run-status.v1.schema.json", it, "복구 실행 조회 응답")
        }

    private fun readSeasonState(): String = mockMvc.perform(
        get("/internal/api/v1/seasons/{seasonId}/recovery-state", SEASON_ID)
            .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION),
    )
        .andExpect(status().isOk)
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andReturn().response.contentAsString.also {
            ContractSchemaSupport.assertValid("recovery-season-state.v1.schema.json", it, "시즌 복구 진단 응답")
        }

    private fun updateMetadata() {
        mockMvc.perform(
            put("/internal/api/v1/seasons/{seasonId}/calendar-metadata", SEASON_ID)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(Path("contracts/examples/season-calendar-metadata.r2.json").readText()),
        ).andExpect(status().isOk)
    }

    private fun verifySeason(
        itemCount: Int,
        itemDigest: String,
        metadataRevision: Int?,
        metadataDigest: String?,
        seasonId: UUID = SEASON_ID,
    ): String = mockMvc.perform(
        put("/internal/api/v1/recovery-runs/{recoveryId}/seasons/{seasonId}/manifest", RECOVERY_ID, seasonId)
            .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "itemCount": $itemCount,
                  "itemDigest": "$itemDigest",
                  "metadataRevision": ${metadataRevision ?: "null"},
                  "metadataDigest": ${metadataDigest?.let { "\"$it\"" } ?: "null"}
                }
                """.trimIndent(),
            ),
    )
        .andExpect(status().isOk)
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.result").value("VERIFIED"))
        .andReturn().response.contentAsString

    private fun completionPayload(states: List<io.baton.cal.recovery.RecoverySeasonState>): String =
        """
        {
          "seasonCount": ${states.size},
          "seasonDigest": "${RecoveryManifestDigest.seasons(states)}"
        }
        """.trimIndent()

    private fun complete(payload: String): String = mockMvc.perform(completionRequest(payload))
        .andExpect(status().isOk)
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.result").value("COMPLETED"))
        .andReturn().response.contentAsString

    private fun completionRequest(payload: String) = put(
        "/internal/api/v1/recovery-runs/{recoveryId}/completion",
        RECOVERY_ID,
    )
        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
        .contentType(MediaType.APPLICATION_JSON)
        .content(payload)

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("f5316f93-d49e-4230-b1d0-9e9c2d079819")
        const val RECOVERY_ID = "92490d0d-b82e-4f94-a041-308b184aaef9"
        const val AUTHORIZATION = "Bearer recovery-manifest-test-token-that-is-long-enough"
    }
}
