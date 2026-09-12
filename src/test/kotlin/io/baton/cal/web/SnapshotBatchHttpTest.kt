package io.baton.cal.web

import io.baton.cal.calendar.events
import io.baton.cal.calendar.parseIcalendar
import io.baton.cal.calendar.requiredPropertyValue
import io.baton.cal.contract.ContractSchemaSupport
import io.baton.cal.persistence.SeasonFeedProjectionRow
import io.baton.cal.projection.SeasonProjectionService
import io.baton.cal.support.PostgreSqlTestContainer
import net.fortuna.ical4j.model.Property
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.util.AopTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.readText

@ImportTestcontainers(PostgreSqlTestContainer::class)
@AutoConfigureMockMvc
@Sql("/reset-database.sql")
@SpringBootTest(properties = ["baton.cal.internal-token=batch-test-internal-token-that-is-long-enough"])
class SnapshotBatchHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcClient,
) {
    @MockitoSpyBean
    private lateinit var projectionService: SeasonProjectionService

    @Test
    fun `100건을 한 번에 반영하고 재전달은 캘린더를 다시 만들지 않는다`() {
        val snapshots = (1..100).map { snapshot(it) }
        val body = batch(snapshots)
        ContractSchemaSupport.assertValid("schedule-snapshot-batch.v1.schema.json", body, "100건 요청")
        assertResults(body, List(100) { "APPLIED" }, snapshots)
        verify(projectionService, times(1)).rebuildWhileLocked(SEASON)
        val projection = projection(SEASON)
        assertThat(projection.representation.parseIcalendar().events()).hasSize(100)

        clearInvocations(projectionService)
        assertResults(body, List(100) { "DUPLICATE" }, snapshots)
        verifyNoInteractions(projectionService)
        assertThat(projection(SEASON)).usingRecursiveComparison().isEqualTo(projection)
    }

    @Test
    fun `중복 역순 취소를 요청 순서로 판정하고 바뀐 시즌만 재생성한다`() {
        val latest = snapshot(1, revision = 2)
        assertResults(batch(listOf(latest)), listOf("APPLIED"), listOf(latest))
        clearInvocations(projectionService)
        val snapshots = listOf(latest, snapshot(1, revision = 1), snapshot(2, OTHER_SEASON), snapshot(1, revision = 3))
        assertResults(batch(snapshots), listOf("DUPLICATE", "STALE", "APPLIED", "APPLIED"), snapshots)
        verify(projectionService, times(1)).rebuildWhileLocked(SEASON)
        verify(projectionService, times(1)).rebuildWhileLocked(OTHER_SEASON)
        val event = projection(SEASON).representation.parseIcalendar().events().single()
        assertThat(event.requiredPropertyValue(Property.SEQUENCE)).isEqualTo("3")
        assertThat(event.requiredPropertyValue(Property.STATUS)).isEqualTo("CANCELLED")
    }

    @Test
    fun `뒤 항목의 충돌은 앞 항목과 수신 기록도 취소하고 수정 재요청을 허용한다`() {
        val first = snapshot(1)
        val conflict = snapshot(2, OTHER_SEASON).put("eventId", first["eventId"].asString())
        val response = submit(batch(listOf(first, conflict)), 409)
        assertThat(response["code"].asString()).isEqualTo("EVENT_ID_CONFLICT")
        assertEmptyDatabase()
        assertResults(batch(listOf(first, snapshot(2, OTHER_SEASON))), listOf("APPLIED", "APPLIED"))
    }

    @Test
    fun `마지막 시즌 캘린더 생성 실패도 앞 시즌과 일정을 함께 취소한다`() {
        val snapshots = listOf(snapshot(1), snapshot(2, OTHER_SEASON))
        val target: SeasonProjectionService = AopTestUtils.getUltimateTargetObject(projectionService)
        doThrow(IllegalStateException("캘린더 생성 실패"))
            .`when`(target).rebuildWhileLocked(OTHER_SEASON)
        submit(batch(snapshots), 500)
        assertEmptyDatabase()
        doCallRealMethod().`when`(target).rebuildWhileLocked(OTHER_SEASON)
        assertResults(batch(snapshots), listOf("APPLIED", "APPLIED"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"snapshots\":[]}", "{\"snapshots\":null}", "{\"snapshots\":[null]}"])
    fun `누락 빈 목록 null 항목은 저장하지 않는다`(body: String) {
        submit(body, 400)
        assertEmptyDatabase()
    }

    @Test
    fun `101건과 중첩 항목의 필드 및 시간 오류는 저장하지 않는다`() {
        val invalids = listOf(
            batch((1..101).map { snapshot(it) }),
            batch(listOf(snapshot(1), snapshot(2).put("summary", ""))),
            batch(listOf(snapshot(1), snapshot(2).put("sourceUpdatedAt", "invalid"))),
        )
        invalids.forEach { submit(it, 400) }
        assertEmptyDatabase()
    }

    @Test
    fun `서로 반대 순서의 시즌 묶음도 동시에 모두 반영한다`() {
        val batches = listOf(
            listOf(snapshot(1), snapshot(2, OTHER_SEASON)),
            listOf(snapshot(3, OTHER_SEASON), snapshot(4)),
        )
        val ready = CountDownLatch(2)
        Executors.newFixedThreadPool(2).use { executor ->
            val tasks = batches.map { snapshots ->
                Callable {
                    ready.countDown()
                    check(ready.await(10, TimeUnit.SECONDS))
                    assertResults(batch(snapshots), listOf("APPLIED", "APPLIED"))
                }
            }
            executor.invokeAll(tasks, 20, TimeUnit.SECONDS).forEach { it.get() }
        }
        for (season in listOf(SEASON, OTHER_SEASON)) {
            val projection = projection(season)
            assertThat(projection.representation.parseIcalendar().events()).hasSize(2)
            assertThat(projectionService.rebuild(season).etag).isEqualTo(projection.etag)
        }
    }

    @Test
    fun `묶음 수신도 내부 인증을 요구한다`() {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(batch(listOf(snapshot(1)))))
            .andExpect(status().isUnauthorized)
        assertEmptyDatabase()
    }

    private fun projection(season: UUID): SeasonFeedProjectionRow = jdbc
        .sql("SELECT season_id, representation, etag, last_modified FROM season_feed_projection WHERE season_id = :season")
        .param("season", season).query(SeasonFeedProjectionRow::class.java).single()

    private fun assertEmptyDatabase() {
        for (table in listOf("source_event_inbox", "calendar_item", "season_feed_projection")) {
            assertThat(jdbc.sql("SELECT count(*) FROM $table").query(Int::class.java).single()).isZero()
        }
    }

    private fun assertResults(body: String, results: List<String>, snapshots: List<JsonNode>? = null) {
        val response = submit(body, 200)["results"].values()
        assertThat(response.map { it["result"].asString() }).containsExactlyElementsOf(results)
        snapshots?.let {
            assertThat(response.map { it["eventId"].asString() })
                .containsExactlyElementsOf(it.map { snapshot -> snapshot["eventId"].asString() })
        }
    }

    private fun submit(body: String, expectedStatus: Int): JsonNode {
        val response = mockMvc.perform(
            post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer batch-test-internal-token-that-is-long-enough")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().`is`(expectedStatus)).andReturn().response.contentAsString
        ContractSchemaSupport.assertValid(
            if (expectedStatus == 200) "schedule-snapshot-batch-result.v1.schema.json" else "api-error.v1.schema.json",
            response, "묶음 수신 응답",
        )
        return JSON.readTree(response)
    }

    private fun batch(snapshots: List<JsonNode>): String = JSON.writeValueAsString(mapOf("snapshots" to snapshots))

    private fun snapshot(index: Int, season: UUID = SEASON, revision: Int = 0): ObjectNode =
        (JSON.readTree(TEMPLATE) as ObjectNode).apply {
            put("eventId", UUID(revision + 1L, index.toLong()).toString())
            put("sourceItemId", UUID(0, index.toLong()).toString())
            put("seasonId", season.toString())
            put("revision", revision)
            put("status", if (revision == 3) "CANCELLED" else "ACTIVE")
            put("sourceUpdatedAt", Instant.parse("2026-08-11T01:00:00Z").plusSeconds(revision.toLong()).toString())
        }

    private companion object {
        const val PATH = "/internal/api/v1/schedule-snapshots/batch"
        val SEASON = UUID.fromString("f5316f93-d49e-4230-b1d0-9e9c2d079819")
        val OTHER_SEASON = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val JSON = JsonMapper()
        val TEMPLATE = Path("contracts/examples/schedule-snapshot.utc-active.json").readText()
    }
}
