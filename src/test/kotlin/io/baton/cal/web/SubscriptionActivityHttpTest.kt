package io.baton.cal.web

import com.jayway.jsonpath.JsonPath
import io.baton.cal.contract.andReturnValid
import io.baton.cal.persistence.CalendarSubscriptionRepository
import io.baton.cal.support.CalIntegrationTest
import io.baton.cal.support.anyArg
import io.baton.cal.support.authorizedDelete
import io.baton.cal.support.authorizedGet
import io.baton.cal.support.createSubscription
import io.baton.cal.support.rotateSubscription
import io.baton.cal.support.whileLocked
import io.micrometer.core.instrument.MeterRegistry
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.util.AopTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

// 공개 피드 조회의 최근 조회일 기록과 내부 조회 경로.
@CalIntegrationTest
class SubscriptionActivityHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jdbc: JdbcClient,
    private val subscriptionRepository: CalendarSubscriptionRepository,
    private val meterRegistry: MeterRegistry,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @Test
    fun `공개 GET·304·HEAD가 최근 조회일을 기록하고 같은 날에는 행을 다시 쓰지 않는다`() {
        val (subscriptionId, token) = createSubscription()
        assertActivity(subscriptionId, null)

        val etag = mockMvc.perform(get(FEED_PATH, token))
            .andExpect(status().isOk)
            .andReturn().response.getHeader(HttpHeaders.ETAG)
        assertActivity(subscriptionId, today())
        val written = rowVersion(subscriptionId)
        mockMvc.perform(get(FEED_PATH, token).header(HttpHeaders.IF_NONE_MATCH, checkNotNull(etag)))
            .andExpect(status().isNotModified)
        assertThat(rowVersion(subscriptionId)).isEqualTo(written)

        storeLastFetchedOn(subscriptionId, today().minusDays(1))
        mockMvc.perform(get(FEED_PATH, token).header(HttpHeaders.IF_NONE_MATCH, etag))
            .andExpect(status().isNotModified)
        assertActivity(subscriptionId, today())
        storeLastFetchedOn(subscriptionId, today().minusDays(1))
        mockMvc.perform(head(FEED_PATH, token)).andExpect(status().isOk)
        assertActivity(subscriptionId, today())
    }

    @Test
    fun `회전과 폐기는 최근 조회일을 비우고 무효한 토큰의 404는 기록하지 않는다`() {
        val (subscriptionId, token) = createSubscription()
        mockMvc.perform(get(FEED_PATH, token)).andExpect(status().isOk)
        assertActivity(subscriptionId, today())

        val rotatedToken = JsonPath.read<String>(mockMvc.rotateSubscription(subscriptionId), "$.token")
        assertActivity(subscriptionId, null)
        mockMvc.perform(get(FEED_PATH, token)).andExpect(status().isNotFound)
        assertActivity(subscriptionId, null)
        mockMvc.perform(get(FEED_PATH, rotatedToken)).andExpect(status().isOk)
        assertActivity(subscriptionId, today())

        mockMvc.perform(authorizedDelete("/internal/api/v1/subscriptions/{subscriptionId}", subscriptionId))
            .andExpect(status().isNoContent)
        assertActivity(subscriptionId, null)
    }

    @Test
    fun `이전 세대 구독은 복원된 날짜를 보고하지 않고 없는 구독은 404다`() {
        val (subscriptionId, token) = createSubscription()
        mockMvc.perform(get(FEED_PATH, token)).andExpect(status().isOk)
        jdbc.sql("UPDATE calendar_subscription SET credential_generation = :generation WHERE id = :id")
            .param("generation", UUID.randomUUID())
            .param("id", subscriptionId)
            .update()

        assertActivity(subscriptionId, null)
        assertThat(subscriptionRepository.findById(subscriptionId)?.lastFetchedOn).isEqualTo(today())
        mockMvc.perform(authorizedGet(ACTIVITY_PATH, UUID.randomUUID()))
            .andExpect(status().isNotFound)
            .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andReturnValid("api-error.v1.schema.json", "없는 구독의 최근 조회일 응답")
    }

    @Test
    fun `잠긴 행은 기다리지 않고 건너뛰며 기록에 실패해도 피드는 응답한다`() {
        val (subscriptionId, token) = createSubscription()
        val failures = meterRegistry.get(FETCH_RECORD_FAILURES).counter()
        val failuresBefore = failures.count()

        // 기록이 행 잠금을 기다리면 요청 트랜잭션의 200ms lock_timeout에 걸려 실패로 집계된다.
        transaction.whileLocked(jdbc, lock = { lockSubscriptionRow(subscriptionId) }) {
            mockMvc.perform(get(FEED_PATH, token)).andExpect(status().isOk)
        }
        assertThat(failures.count()).isEqualTo(failuresBefore)
        assertActivity(subscriptionId, null)

        val target: CalendarSubscriptionRepository = AopTestUtils.getUltimateTargetObject(subscriptionRepository)
        doThrow(QueryTimeoutException("simulated fetch record failure")).`when`(target).recordFetch(
            anyArg(String::class.java, ""),
            anyArg(UUID::class.java, UUID(0, 0)),
            anyArg(LocalDate::class.java, LocalDate.EPOCH),
        )
        mockMvc.perform(get(FEED_PATH, token)).andExpect(status().isOk)
        assertThat(failures.count()).isEqualTo(failuresBefore + 1)
        assertActivity(subscriptionId, null)
    }

    private fun createSubscription(): Pair<UUID, String> {
        val credential = mockMvc.createSubscription(SEASON_ID)
        return UUID.fromString(JsonPath.read(credential, "$.subscriptionId")) to JsonPath.read(credential, "$.token")
    }

    private fun assertActivity(subscriptionId: UUID, expected: LocalDate?) {
        mockMvc.perform(authorizedGet(ACTIVITY_PATH, subscriptionId))
            .andExpect(status().isOk)
            .andExpect(header().stringValues(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.subscriptionId").value(subscriptionId.toString()))
            .andExpect(jsonPath("$.lastFetchedOn", equalTo(expected?.toString())))
            .andReturnValid("subscription-activity.v1.schema.json", "구독 최근 조회일 응답")
    }

    // 행을 다시 쓰면 PostgreSQL의 행 버전 xmin이 바뀐다.
    private fun rowVersion(subscriptionId: UUID): String =
        jdbc.sql("SELECT xmin::text FROM calendar_subscription WHERE id = :id")
            .param("id", subscriptionId)
            .query(String::class.java)
            .single()

    private fun storeLastFetchedOn(subscriptionId: UUID, date: LocalDate) {
        jdbc.sql("UPDATE calendar_subscription SET last_fetched_on = :date WHERE id = :id")
            .param("date", date)
            .param("id", subscriptionId)
            .update()
    }

    private fun lockSubscriptionRow(subscriptionId: UUID) {
        jdbc.sql("SELECT id FROM calendar_subscription WHERE id = :id FOR UPDATE")
            .param("id", subscriptionId)
            .query(UUID::class.java)
            .single()
    }

    private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    private companion object {
        const val SEASON_ID = "30000000-0000-0000-0000-000000000001"
        const val FEED_PATH = "/calendars/v1/{token}.ics"
        const val ACTIVITY_PATH = "/internal/api/v1/subscriptions/{subscriptionId}/activity"
        const val FETCH_RECORD_FAILURES = "baton.cal.subscription.fetch.record.failures"
    }
}
