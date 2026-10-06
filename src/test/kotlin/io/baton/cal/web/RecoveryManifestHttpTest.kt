package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import io.baton.cal.contract.andReturnValid
import io.baton.cal.contract.contractExample
import io.baton.cal.persistence.AdvisoryLockRepository
import io.baton.cal.persistence.RecoveryManifestRepository
import io.baton.cal.persistence.SeasonCalendarMetadataRepository
import io.baton.cal.recovery.RecoveryManifestDigest
import io.baton.cal.recovery.RecoverySeasonState
import io.baton.cal.support.RecoveryModeIntegrationTest
import io.baton.cal.support.authorizedGet
import io.baton.cal.support.authorizedPut
import io.baton.cal.support.ingestSnapshotExample
import io.baton.cal.support.jsonContent
import io.baton.cal.support.updateSeasonCalendarMetadata
import io.baton.cal.support.whileLocked
import org.springframework.test.json.JsonContent
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.jdbc.JdbcTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

@RecoveryModeIntegrationTest
class RecoveryManifestHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val repository: RecoveryManifestRepository,
    private val metadataRepository: SeasonCalendarMetadataRepository,
    private val lockRepository: AdvisoryLockRepository,
    private val jdbcClient: JdbcClient,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @Test
    fun `복구 상태 조회는 진행과 완료를 구분하고 이후 원본 변경에도 완료 기록을 유지한다`() {
        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-active-r0.json")
        val state = currentState()
        val verified = verifySeason(state)
        val before = storedManifests()

        readRunStatus("IN_PROGRESS", 1)
        val completed = complete(completionPayload(listOf(state)))
        val completedAt: String = JsonPath.read(completed, "$.completedAt")
        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-cancelled.json")
        assertThat(verifySeason(state)).isEqualTo(verified)
        mockMvc.perform(manifestRequest(contractExample("recovery-season-manifest.json")))
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
    @ValueSource(booleans = [false, true])
    fun `시즌 이름 개정 번호와 다이제스트 중 하나만 보내면 검증 기록을 남기지 않는다`(withRevision: Boolean) {
        updateMetadata()
        val state = currentState()
        val unpaired = if (withRevision) state.copy(metadataDigest = null) else state.copy(metadataRevision = null)
        mockMvc.perform(seasonManifestRequest(unpaired))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andReturnValid("api-error.v1.schema.json", "복구 시즌 이름 짝 오류 응답")
        assertThat(repository.listVerifiedSeasonStates(UUID.fromString(RECOVERY_ID))).isEmpty()
        verifySeason(state)
    }

    @Test
    fun `시즌 진단은 복구 실행이 없어도 일정과 이름 불일치를 나누어 확인한다`() {
        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-cancelled.json")
        val initial = readSeasonState()
        assertThat(JsonPath.read<Any?>(initial, "$.metadataRevision")).isNull()
        assertThat(JsonPath.read<Any?>(initial, "$.metadataDigest")).isNull()
        updateMetadata()
        val named = readSeasonState()
        assertThat(JsonPath.read<String>(named, "$.itemDigest"))
            .isEqualTo(JsonPath.read<String>(initial, "$.itemDigest"))
        // 복원 스모크와 BATON이 쓰는 진단 예시와 실제 응답이 같아야 한다.
        assertThat(JsonContent(named)).isStrictlyEqualTo(contractExample("recovery-season-state.zoned-cancelled.json"))
        assertThat(JdbcTestUtils.countRowsInTable(jdbcClient, "recovery_season_manifest")).isZero()
        assertThat(JdbcTestUtils.countRowsInTable(jdbcClient, "recovery_run_completion")).isZero()
    }

    @Test
    fun `이름만 받은 시즌도 복구 진단을 반환한다`() {
        updateMetadata()
        val metadataOnly = readSeasonState()
        assertThat(JsonPath.read<Int>(metadataOnly, "$.itemCount")).isZero()
    }

    @Test
    fun `복원 스모크의 고정 매니페스트는 최신 취소와 시즌 이름을 모두 요구한다`() {
        val manifest = contractExample("recovery-season-manifest.zoned-cancelled.json")
        val completion = contractExample("recovery-run-completion.zoned-cancelled.json")
        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-active-r2.json")
        updateMetadata()
        mockMvc.perform(manifestRequest(manifest)).andExpect(status().isConflict)
        mockMvc.perform(completionRequest(completion)).andExpect(status().isConflict)
        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-cancelled.json")
        mockMvc.perform(manifestRequest(manifest))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value("VERIFIED"))
        val first = complete(completion)
        assertThat(complete(completion)).isEqualTo(first)
    }

    @Test
    fun `전체 시즌 상태가 일치하면 복구 완료 신호를 멱등하게 반환한다`() {
        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-active-r0.json")
        updateMetadata()
        val state = currentState()

        verifySeason(state)
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
        transaction.whileLocked(
            jdbcClient,
            lock = {
                if (lock == "run") {
                    lockRepository.lockRecoveryRun(UUID.fromString(RECOVERY_ID))
                } else {
                    jdbcClient.sql("LOCK TABLE calendar_item IN ROW EXCLUSIVE MODE").update()
                }
            },
        ) {
            mockMvc.perform(completionRequest(payload))
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(content().json(contractExample("api-error.service-busy.json")))
                .andReturnValid("api-error.v1.schema.json", "복구 잠금 시간 초과")
        }
        assertThat(repository.findCompletion(UUID.fromString(RECOVERY_ID))).isNull()
        complete(payload)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `매니페스트 검증은 복구 완료 전까지만 시즌 잠금을 기다린다`(completed: Boolean) {
        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-active-r0.json")
        val state = currentState()
        val verified = verifySeason(state)
        if (completed) complete(completionPayload(listOf(state)))

        transaction.whileLocked(jdbcClient, lock = { lockRepository.lockSeason(SEASON_ID) }) {
            if (completed) {
                assertThat(verifySeason(state)).isEqualTo(verified)
                mockMvc.perform(seasonManifestRequest(state.copy(itemDigest = "0".repeat(64))))
                    .andExpect(status().isConflict)
                    .andExpect(jsonPath("$.code").value("RECOVERY_RUN_CONFLICT"))
            } else {
                mockMvc.perform(seasonManifestRequest(state))
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
        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-active-r0.json")
        val initial = currentState()
        verifySeason(initial)

        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-active-r2.json")
        assertCompletionMismatch(listOf(initial))

        val current = currentState()
        verifySeason(current)
        complete(completionPayload(listOf(current)))
    }

    @Test
    fun `현재 데이터에 없는 빈 시즌을 매니페스트에 추가하면 완료하지 않는다`() {
        val extraSeasonId = UUID.fromString("ea9696eb-4a62-4f37-a11d-9cb040b0fd03")
        val extra = currentState(extraSeasonId)
        verifySeason(extra)

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

    private fun readRunStatus(expectedStatus: String, seasonCount: Int): String = mockMvc
        .perform(authorizedGet("/internal/api/v1/recovery-runs/{recoveryId}", RECOVERY_ID))
        .andExpect(status().isOk)
        .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.status").value(expectedStatus))
        .andExpect(jsonPath("$.verifiedSeasonCount").value(seasonCount))
        .andExpect(jsonPath("$.recoveryMode").value(true))
        .andReturnValid("recovery-run-status.v1.schema.json", "복구 실행 조회 응답")

    private fun readSeasonState(): String = mockMvc
        .perform(authorizedGet("/internal/api/v1/seasons/{seasonId}/recovery-state", SEASON_ID))
        .andExpect(status().isOk)
        .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andReturnValid("recovery-season-state.v1.schema.json", "시즌 복구 진단 응답")

    private fun updateMetadata() {
        mockMvc.updateSeasonCalendarMetadata(SEASON_ID, contractExample("season-calendar-metadata.r2.json"))
    }

    private fun verifySeason(state: RecoverySeasonState): String = mockMvc.perform(seasonManifestRequest(state))
        .andExpect(status().isOk)
        .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.result").value("VERIFIED"))
        .andReturnValid("recovery-season-manifest-result.v1.schema.json", "시즌 복구 매니페스트 검증 응답")

    // 경로의 시즌 ID를 뺀 시즌 대조값이 매니페스트 본문이다.
    private fun seasonManifestRequest(state: RecoverySeasonState) = manifestRequest(
        JSON.writeValueAsString(JSON.valueToTree<ObjectNode>(state).apply { remove("seasonId") }),
        state.seasonId,
    )

    private fun manifestRequest(body: String, seasonId: UUID = SEASON_ID) =
        authorizedPut("/internal/api/v1/recovery-runs/{recoveryId}/seasons/{seasonId}/manifest", RECOVERY_ID, seasonId)
            .jsonContent(body)

    private fun completionPayload(states: List<RecoverySeasonState>): String = JSON.writeValueAsString(
        mapOf("seasonCount" to states.size, "seasonDigest" to RecoveryManifestDigest.seasons(states)),
    )

    private fun complete(payload: String): String = mockMvc.perform(completionRequest(payload))
        .andExpect(status().isOk)
        .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.result").value("COMPLETED"))
        .andReturnValid("recovery-run-completion-result.v1.schema.json", "전체 복구 완료 응답")

    private fun completionRequest(payload: String) =
        authorizedPut("/internal/api/v1/recovery-runs/{recoveryId}/completion", RECOVERY_ID).jsonContent(payload)

    private fun assertCompletionMismatch(states: List<RecoverySeasonState>) {
        mockMvc.perform(completionRequest(completionPayload(states)))
            .andExpect(status().isConflict)
            .andExpect(content().json(contractExample("api-error.recovery-manifest-mismatch.json")))
    }

    private companion object {
        val JSON = JsonMapper()
        val SEASON_ID: UUID = UUID.fromString("f5316f93-d49e-4230-b1d0-9e9c2d079819")
        const val RECOVERY_ID = "92490d0d-b82e-4f94-a041-308b184aaef9"
    }
}
