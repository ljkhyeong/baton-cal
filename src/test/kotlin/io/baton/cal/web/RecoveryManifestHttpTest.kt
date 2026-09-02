package io.baton.cal.web

import io.baton.cal.contract.ContractSchemaSupport
import io.baton.cal.persistence.RecoveryManifestRepository
import io.baton.cal.recovery.RecoveryManifestDigest
import io.baton.cal.support.PostgreSqlTestContainer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
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
) {
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
