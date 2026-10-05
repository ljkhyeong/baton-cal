package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import io.baton.cal.contract.andReturnValid
import io.baton.cal.persistence.RecoveryManifestRepository
import io.baton.cal.persistence.SeasonCalendarMetadataRepository
import io.baton.cal.persistence.SeasonProjectionLockRepository
import io.baton.cal.recovery.RecoveryManifestDigest
import io.baton.cal.recovery.RecoverySeasonState
import io.baton.cal.support.PostgreSqlTestContainer
import io.baton.cal.support.TEST_INTERNAL_TOKEN
import io.baton.cal.support.authorizedGet
import io.baton.cal.support.authorizedPost
import io.baton.cal.support.bearer
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
import org.springframework.test.jdbc.JdbcTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
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
        "baton.cal.internal-token=$TEST_INTERNAL_TOKEN",
        "baton.cal.recovery-mode=true",
    ],
)
@Sql("/reset-database.sql")
class RecoveryManifestHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val repository: RecoveryManifestRepository,
    private val metadataRepository: SeasonCalendarMetadataRepository,
    private val seasonLockRepository: SeasonProjectionLockRepository,
    private val jdbcClient: JdbcClient,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @Test
    fun `복구 상태 조회는 진행과 완료를 구분하고 이후 원본 변경에도 완료 기록을 유지한다`() {
        ingest("schedule-snapshot.zoned-active-r0.json")
        val state = currentState()
        val verified = verifySeason(state.itemCount, state.itemDigest, null, null)
        val before = storedManifests()

        readRunStatus("IN_PROGRESS", 1)
        val completed = complete(completionPayload(listOf(state)))
        val completedAt: String = JsonPath.read(completed, "$.completedAt")
        ingest("schedule-snapshot.zoned-cancelled.json")
        assertThat(verifySeason(state.itemCount, state.itemDigest, null, null)).isEqualTo(verified)
        mockMvc.perform(manifestRequest(Path("contracts/examples/recovery-season-manifest.json").readText()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RECOVERY_RUN_CONFLICT"))
        val status = readRunStatus("COMPLETED", 1)

        assertThat(JsonPath.read<String>(status, "$.completedAt")).isEqualTo(completedAt)
        assertThat(storedManifests()).isEqualTo(before)
    }

    @Test
    fun `빈 데이터의 완료 기록도 진행 상태 조회에서 찾는다`() {
        complete(completionPayload(emptyList()))
        readRunStatus("COMPLETED", 0)
    }

    @ParameterizedTest
    @ValueSource(strings = ["itemCount", "metadataRevision"])
    fun `복구 매니페스트의 소수 건수와 개정 번호는 검증 기록을 남기지 않는다`(field: String) {
        updateMetadata()
        val state = currentState()
        val payload = """
            {
              "itemCount": 0,
              "itemDigest": "${state.itemDigest}",
              "metadataRevision": 2,
              "metadataDigest": "${state.metadataDigest}"
            }
        """.trimIndent()
        val invalid = when (field) {
            "itemCount" -> payload.replace("\"itemCount\": 0", "\"itemCount\": 0.5")
            else -> payload.replace("\"metadataRevision\": 2", "\"metadataRevision\": 2.5")
        }
        mockMvc.perform(manifestRequest(invalid))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andReturnValid("api-error.v1.schema.json", "복구 정수 입력 오류 응답")
        assertThat(repository.listVerifiedSeasonStates(UUID.fromString(RECOVERY_ID))).isEmpty()
        verifySeason(0, state.itemDigest, 2, state.metadataDigest)
    }

    @Test
    fun `소수인 시즌 수는 복구 완료 기록을 남기지 않는다`() {
        val payload = completionPayload(emptyList())
        mockMvc.perform(completionRequest(payload.replace("\"seasonCount\": 0", "\"seasonCount\": 0.5")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andReturnValid("api-error.v1.schema.json", "복구 완료 정수 입력 오류 응답")
        assertThat(repository.findCompletion(UUID.fromString(RECOVERY_ID))).isNull()
        complete(payload)
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
        // 복원 스모크와 BATON이 쓰는 진단 예시와 실제 응답이 같아야 한다.
        assertThat(JSON.readTree(named))
            .isEqualTo(JSON.readTree(Path("contracts/examples/recovery-season-state.zoned-cancelled.json").readText()))
        assertThat(JdbcTestUtils.countRowsInTable(jdbcClient, "recovery_season_manifest")).isZero()
        assertThat(JdbcTestUtils.countRowsInTable(jdbcClient, "recovery_run_completion")).isZero()
    }

    @Test
    fun `복구 진단은 인증과 UUID를 확인하고 없는 수신 기록은 404다`() {
        listOf(
            "/internal/api/v1/recovery-runs/$RECOVERY_ID",
            "/internal/api/v1/seasons/$SEASON_ID/recovery-state",
        ).forEach { path ->
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized)
            mockMvc.perform(authorizedGet(path))
                .andExpect(status().isNotFound)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            val invalidPath = path.replace(RECOVERY_ID, "invalid").replace(SEASON_ID.toString(), "invalid")
            mockMvc.perform(authorizedGet(invalidPath)).andExpect(status().isBadRequest)
        }
        updateMetadata()
        val metadataOnly = readSeasonState()
        assertThat(JsonPath.read<Int>(metadataOnly, "$.itemCount")).isZero()
    }

    @Test
    fun `복원 스모크의 고정 매니페스트는 최신 취소와 시즌 이름을 모두 요구한다`() {
        val manifest = Path("contracts/examples/recovery-season-manifest.zoned-cancelled.json").readText()
        val completion = Path("contracts/examples/recovery-run-completion.zoned-cancelled.json").readText()
        ingest("schedule-snapshot.zoned-active-r2.json")
        updateMetadata()
        mockMvc.perform(manifestRequest(manifest)).andExpect(status().isConflict)
        mockMvc.perform(completionRequest(completion)).andExpect(status().isConflict)
        ingest("schedule-snapshot.zoned-cancelled.json")
        mockMvc.perform(manifestRequest(manifest))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("VERIFIED"))
        val first = complete(completion)
        assertThat(complete(completion)).isEqualTo(first)
    }

    @Test
    fun `전체 시즌 상태가 일치하면 복구 완료 신호를 멱등하게 반환한다`() {
        ingest("schedule-snapshot.zoned-active-r0.json")
        updateMetadata()
        val state = currentState()

        verifySeason(state.itemCount, state.itemDigest, state.metadataRevision, state.metadataDigest)
        val completionPayload = completionPayload(listOf(state))
        val first = complete(completionPayload)
        val duplicate = complete(completionPayload)

        assertThat(duplicate).isEqualTo(first)
        mockMvc.perform(completionRequest(completionPayload(emptyList())))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RECOVERY_RUN_CONFLICT"))
        assertThat(complete(completionPayload)).isEqualTo(first)
    }

    @ParameterizedTest
    @ValueSource(strings = ["run", "state"])
    fun `복구 잠금 대기가 끝나면 503과 재시도 간격을 반환한다`(lock: String) {
        val payload = completionPayload(emptyList())
        whileLocked(
            lock = {
                if (lock == "run") {
                    repository.lockRecoveryRun(UUID.fromString(RECOVERY_ID))
                } else {
                    jdbcClient.sql("LOCK TABLE calendar_item IN ROW EXCLUSIVE MODE").update()
                }
            },
        ) {
            mockMvc.perform(completionRequest(payload))
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(content().json(Path("contracts/examples/api-error.service-busy.json").readText()))
                .andReturnValid("api-error.v1.schema.json", "복구 잠금 시간 초과")
        }
        assertThat(repository.findCompletion(UUID.fromString(RECOVERY_ID))).isNull()
        complete(payload)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `매니페스트 검증은 복구 완료 전까지만 시즌 잠금을 기다린다`(completed: Boolean) {
        ingest("schedule-snapshot.zoned-active-r0.json")
        val state = currentState()
        val verified = verifySeason(state.itemCount, state.itemDigest, null, null)
        if (completed) complete(completionPayload(listOf(state)))

        whileLocked(lock = { seasonLockRepository.acquire(SEASON_ID) }) {
            if (completed) {
                assertThat(verifySeason(state.itemCount, state.itemDigest, null, null)).isEqualTo(verified)
                mockMvc.perform(seasonManifestRequest(state.itemCount, "0".repeat(64), null, null))
                    .andExpect(status().isConflict)
                    .andExpect(jsonPath("$.code").value("RECOVERY_RUN_CONFLICT"))
            } else {
                mockMvc.perform(seasonManifestRequest(state.itemCount, state.itemDigest, null, null))
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
            }
        }
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
        val initial = currentState()
        verifySeason(initial.itemCount, initial.itemDigest, null, null)

        ingest("schedule-snapshot.zoned-active-r2.json")
        assertCompletionMismatch(listOf(initial))

        val current = currentState()
        verifySeason(current.itemCount, current.itemDigest, null, null)
        complete(completionPayload(listOf(current)))
    }

    @Test
    fun `현재 데이터에 없는 빈 시즌을 매니페스트에 추가하면 완료하지 않는다`() {
        val extraSeasonId = UUID.fromString("ea9696eb-4a62-4f37-a11d-9cb040b0fd03")
        val extra = currentState(extraSeasonId)
        verifySeason(
            extra.itemCount,
            extra.itemDigest,
            extra.metadataRevision,
            extra.metadataDigest,
            extraSeasonId,
        )

        assertCompletionMismatch(listOf(extra))
    }

    private fun currentState(seasonId: UUID = SEASON_ID) = RecoveryManifestDigest.seasonState(
        seasonId,
        repository.listItemStates(seasonId),
        metadataRepository.findBySeasonId(seasonId)?.let { it.revision to it.displayName },
    )

    private fun storedManifests() = jdbcClient.sql(
        "SELECT * FROM recovery_season_manifest WHERE recovery_id = :recoveryId",
    )
        .param("recoveryId", UUID.fromString(RECOVERY_ID))
        .query()
        .listOfRows()

    private fun ingest(fileName: String) {
        mockMvc.perform(
            authorizedPost("/internal/api/v1/schedule-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(Path("contracts/examples", fileName).readText()),
        ).andExpect(status().isOk)
    }

    private fun readRunStatus(expectedStatus: String, seasonCount: Int): String = mockMvc
        .perform(authorizedGet("/internal/api/v1/recovery-runs/{recoveryId}", RECOVERY_ID))
        .andExpect(status().isOk)
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.status").value(expectedStatus))
        .andExpect(jsonPath("$.verifiedSeasonCount").value(seasonCount))
        .andExpect(jsonPath("$.recoveryMode").value(true))
        .andReturnValid("recovery-run-status.v1.schema.json", "복구 실행 조회 응답")

    private fun readSeasonState(): String = mockMvc
        .perform(authorizedGet("/internal/api/v1/seasons/{seasonId}/recovery-state", SEASON_ID))
        .andExpect(status().isOk)
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andReturnValid("recovery-season-state.v1.schema.json", "시즌 복구 진단 응답")

    private fun updateMetadata() {
        mockMvc.perform(
            put("/internal/api/v1/seasons/{seasonId}/calendar-metadata", SEASON_ID)
                .bearer()
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
        seasonManifestRequest(itemCount, itemDigest, metadataRevision, metadataDigest, seasonId),
    )
        .andExpect(status().isOk)
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.result").value("VERIFIED"))
        .andReturnValid("recovery-season-manifest-result.v1.schema.json", "시즌 복구 매니페스트 검증 응답")

    private fun seasonManifestRequest(
        itemCount: Int,
        itemDigest: String,
        metadataRevision: Int?,
        metadataDigest: String?,
        seasonId: UUID = SEASON_ID,
    ) = manifestRequest(
        """
        {
          "itemCount": $itemCount,
          "itemDigest": "$itemDigest",
          "metadataRevision": ${metadataRevision ?: "null"},
          "metadataDigest": ${metadataDigest?.let { "\"$it\"" } ?: "null"}
        }
        """.trimIndent(),
        seasonId,
    )

    private fun manifestRequest(body: String, seasonId: UUID = SEASON_ID) =
        put("/internal/api/v1/recovery-runs/{recoveryId}/seasons/{seasonId}/manifest", RECOVERY_ID, seasonId)
            .bearer()
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)

    private fun completionPayload(states: List<RecoverySeasonState>): String =
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
        .andReturnValid("recovery-run-completion-result.v1.schema.json", "전체 복구 완료 응답")

    private fun completionRequest(payload: String) =
        put("/internal/api/v1/recovery-runs/{recoveryId}/completion", RECOVERY_ID)
            .bearer()
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload)

    private fun assertCompletionMismatch(states: List<RecoverySeasonState>) {
        mockMvc.perform(completionRequest(completionPayload(states)))
            .andExpect(status().isConflict)
            .andExpect(content().json(Path("contracts/examples/api-error.recovery-manifest-mismatch.json").readText()))
    }

    // 다른 트랜잭션이 잠금을 잡은 동안 짧은 lock_timeout으로 요청을 실행하고 그 트랜잭션은 롤백한다.
    private fun whileLocked(lock: () -> Unit, request: () -> Unit) {
        Executors.newSingleThreadExecutor().use { executor ->
            transaction.executeWithoutResult {
                lock()
                executor.submit {
                    transaction.executeWithoutResult { rollback ->
                        rollback.setRollbackOnly()
                        jdbcClient.sql("SET LOCAL lock_timeout TO '200ms'").update()
                        request()
                    }
                }.get(5, TimeUnit.SECONDS)
            }
        }
    }

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("f5316f93-d49e-4230-b1d0-9e9c2d079819")
        const val RECOVERY_ID = "92490d0d-b82e-4f94-a041-308b184aaef9"
        val JSON = JsonMapper()
    }
}
