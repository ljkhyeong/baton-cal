package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import io.baton.cal.contract.andReturnValid
import io.baton.cal.contract.contractExample
import io.baton.cal.persistence.AdvisoryLockRepository
import io.baton.cal.persistence.SeasonFeedProjectionRow
import io.baton.cal.support.CalIntegrationTest
import io.baton.cal.support.authorizedPost
import io.baton.cal.support.createSubscription
import io.baton.cal.support.feedProjection
import io.baton.cal.support.ingestSnapshot
import io.baton.cal.support.ingestSnapshotExample
import io.baton.cal.support.snapshotExample
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.util.AopTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// 렌더러나 내장 시간대 데이터를 바꾼 배포 뒤 이전 출력으로 저장된 시즌 투영을 전체 재생성으로 바꾼다.
@CalIntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class ProjectionRebuildAllHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcClient,
    private val lockRepository: AdvisoryLockRepository,
) {
    @Test
    fun `이전 출력으로 저장된 시즌만 바꾸고 나머지 시즌의 검증 값과 구독 주소를 유지한다`() {
        rebuildAll(seasonCount = 0, changedSeasonCount = 0)
        val current = ingestSeasons()
        val token = JsonPath.read<String>(mockMvc.createSubscription(FIRST_SEASON.toString()), "$.token")
        val previousEtag = storePreviousOutput(FIRST_SEASON)
        val startedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS)

        rebuildAll(seasonCount = 2, changedSeasonCount = 1)

        val rebuilt = jdbc.feedProjection(FIRST_SEASON)
        assertThat(rebuilt.representation).isEqualTo(current.getValue(FIRST_SEASON).representation)
        assertThat(rebuilt.etag).isEqualTo(current.getValue(FIRST_SEASON).etag)
        assertThat(rebuilt.lastModified).isAfterOrEqualTo(startedAt)
        assertThat(jdbc.feedProjection(SECOND_SEASON)).usingRecursiveComparison()
            .isEqualTo(current.getValue(SECOND_SEASON))
        mockMvc.perform(get("/calendars/v1/{token}.ics", token).header(HttpHeaders.IF_NONE_MATCH, previousEtag))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, rebuilt.etag))
            .andExpect(content().bytes(rebuilt.representation))

        rebuildAll(seasonCount = 2, changedSeasonCount = 0)
        assertThat(jdbc.feedProjection(FIRST_SEASON)).usingRecursiveComparison().isEqualTo(rebuilt)
    }

    @Test
    fun `한 시즌의 잠금 실패는 앞서 바꾼 시즌을 유지하고 재요청이 남은 시즌을 처리한다`(output: CapturedOutput) {
        val current = ingestSeasons()
        current.keys.forEach(::storePreviousOutput)
        val target: AdvisoryLockRepository = AopTestUtils.getUltimateTargetObject(lockRepository)
        doThrow(CannotAcquireLockException("simulated lock timeout")).`when`(target).lockSeason(SECOND_SEASON)

        mockMvc.perform(authorizedPost(REBUILD_ALL_PATH))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
            .andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
        assertThat(output.all).contains("Projection rebuild stopped at season $SECOND_SEASON")
        assertThat(jdbc.feedProjection(FIRST_SEASON).etag).isEqualTo(current.getValue(FIRST_SEASON).etag)
        assertThat(jdbc.feedProjection(SECOND_SEASON).etag).isNotEqualTo(current.getValue(SECOND_SEASON).etag)

        doCallRealMethod().`when`(target).lockSeason(SECOND_SEASON)
        rebuildAll(seasonCount = 2, changedSeasonCount = 1)
        assertThat(jdbc.feedProjection(SECOND_SEASON).etag).isEqualTo(current.getValue(SECOND_SEASON).etag)
    }

    @Test
    fun `시간대 데이터 갱신으로 공백이 된 저장 일정이 있어도 시즌을 처리한다`() {
        val season = UUID.fromString(JsonPath.read(contractExample("schedule-snapshot.zoned-point-active.json"), "$.seasonId"))
        mockMvc.ingestSnapshotExample("schedule-snapshot.zoned-point-active.json")
        jdbc.sql(
            """
            UPDATE calendar_item
            SET starts_at_local = TIMESTAMP '2026-03-08 02:30:00', zone_id = 'America/New_York'
            WHERE season_id = :seasonId
            """.trimIndent(),
        )
            .param("seasonId", season)
            .update()

        mockMvc.perform(authorizedPost("/internal/api/v1/projections/seasons/{seasonId}/rebuild", season))
            .andExpect(status().isOk)
        mockMvc.ingestSnapshot(
            snapshotExample("schedule-snapshot.utc-active.json") { put("seasonId", season.toString()) },
        )
        rebuildAll(seasonCount = 1, changedSeasonCount = 0)
        assertThat(jdbc.feedProjection(season).representation.decodeToString().replace("\r\n ", ""))
            .contains("DTSTART;TZID=America/New_York:20260308T023000")
    }

    /** 두 시즌에 일정을 하나씩 받고 현재 렌더러가 만든 투영을 시즌별로 반환한다. */
    private fun ingestSeasons(): Map<UUID, SeasonFeedProjectionRow> =
        listOf(FIRST_SEASON, SECOND_SEASON).withIndex().associate { (index, season) ->
            val itemNumber = index + 1L
            mockMvc.ingestSnapshot(
                snapshotExample("schedule-snapshot.utc-active.json") {
                    put("eventId", UUID(1, itemNumber).toString())
                    put("sourceItemId", UUID(0, itemNumber).toString())
                    put("seasonId", season.toString())
                },
            )
            season to jdbc.feedProjection(season)
        }

    /** 이전 렌더러가 만든 것처럼 다른 바이트와 과거 Last-Modified를 저장하고 그 ETag를 반환한다. */
    private fun storePreviousOutput(seasonId: UUID): String {
        // 줄 접기 길이 25보다 짧아 한 물리 줄에 있는 속성을 지워 이전 출력을 만든다.
        val current = jdbc.feedProjection(seasonId).representation.decodeToString()
        val previous = current.replace("CALSCALE:GREGORIAN\r\n", "")
        check(previous != current) { "이전 출력을 만들지 못했습니다" }
        val bytes = previous.encodeToByteArray()
        val etag = "\"${MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()}\""
        jdbc.sql(
            """
            UPDATE season_feed_projection
            SET representation = :bytes, etag = :etag, last_modified = :lastModified
            WHERE season_id = :seasonId
            """.trimIndent(),
        )
            .param("bytes", bytes)
            .param("etag", etag)
            .param("lastModified", PREVIOUS_LAST_MODIFIED.atOffset(ZoneOffset.UTC))
            .param("seasonId", seasonId)
            .update()
        return etag
    }

    private fun rebuildAll(seasonCount: Int, changedSeasonCount: Int) {
        mockMvc.perform(authorizedPost(REBUILD_ALL_PATH))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.seasonCount").value(seasonCount))
            .andExpect(jsonPath("$.changedSeasonCount").value(changedSeasonCount))
            .andReturnValid("projection-rebuild-all-result.v1.schema.json", "전체 시즌 투영 재생성 응답")
    }

    private companion object {
        const val REBUILD_ALL_PATH = "/internal/api/v1/projections/rebuild"
        val FIRST_SEASON: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val SECOND_SEASON: UUID = UUID.fromString("10000000-0000-0000-0000-000000000002")
        val PREVIOUS_LAST_MODIFIED: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
