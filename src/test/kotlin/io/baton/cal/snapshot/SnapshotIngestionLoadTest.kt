package io.baton.cal.snapshot

import io.baton.cal.calendar.events
import io.baton.cal.calendar.parseIcalendar
import io.baton.cal.calendar.requiredPropertyValue
import io.baton.cal.projection.SeasonProjectionService
import io.baton.cal.support.PostgreSqlTestContainer
import io.micrometer.core.instrument.MeterRegistry
import net.fortuna.ical4j.model.Property
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.test.context.jdbc.Sql
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.math.ceil

@Tag("ingestion-load")
@ImportTestcontainers(PostgreSqlTestContainer::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["baton.cal.internal-token=ingestion-load-test-token-that-is-long-enough"],
)
@Sql("/reset-database.sql")
class SnapshotIngestionLoadTest @Autowired constructor(
    @param:Value("\${local.server.port}") private val port: Int,
    private val projectionService: SeasonProjectionService,
    private val meterRegistry: MeterRegistry,
) {
    @Test
    fun `최초 적재와 같은 시즌 동시 변경 중 조건부 GET의 지연과 최종 피드를 확인한다`() {
        val itemCount = System.getProperty("baton.cal.load.item-count", "1000").toInt()
        val batchSize = System.getProperty("baton.cal.load.batch-size", "1").toInt()
        require(itemCount >= 2) { "동시 수신 측정에는 두 항목 이상이 필요합니다" }
        require(batchSize in 1..100) { "묶음 크기는 1부터 100까지입니다" }
        println("LOAD itemCount=$itemCount batchSize=$batchSize")
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().use { client ->
            val initialLatency = ConcurrentLinkedQueue<Double>()
            val initialStarted = System.nanoTime()
            (1..itemCount).toList().chunked(batchSize).forEach { indices ->
                ingestGroup(client, indices.map { snapshot(it, 0) }, batchSize, initialLatency)
            }
            report("initial", initialLatency, initialStarted)

            val credential = send(client, "/internal/api/v1/subscriptions", """{"seasonId":"$SEASON_ID"}""")
            assertThat(credential.statusCode()).isEqualTo(201)
            val feedPath = URI.create(JSON.readTree(credential.body())["feedUrl"].asString()).rawPath
            var etag = send(client, feedPath).headers().firstValue("ETag").orElseThrow()
            val updating = AtomicBoolean(true)
            val updateLatency = ConcurrentLinkedQueue<Double>()
            val replayLatency = ConcurrentLinkedQueue<Double>()
            val readLatency = ConcurrentLinkedQueue<Double>()
            val readStatuses = ConcurrentLinkedQueue<Int>()
            val lockTimer = meterRegistry.get("baton.cal.projection.lock.acquire").timer()
            val previousLockTime = lockTimer.totalTime(TimeUnit.MILLISECONDS)
            val previousLockCount = lockTimer.count()
            val updateStarted = System.nanoTime()
            Executors.newFixedThreadPool(3).use { executor ->
                val reader = executor.submit {
                    while (updating.get()) {
                        val started = System.nanoTime()
                        val response = send(client, feedPath, etag = etag)
                        readLatency.add(elapsedMillis(started))
                        readStatuses.add(response.statusCode())
                        assertThat(response.statusCode()).isIn(200, 304)
                        if (response.statusCode() == 304) assertThat(response.body()).isEmpty()
                        etag = response.headers().firstValue("ETag").orElseThrow()
                        Thread.sleep(25)
                    }
                }
                try {
                    val writers = (1..2).map { first ->
                        executor.submit {
                            (first..itemCount step 2).toList().chunked(batchSize).forEach { indices ->
                                ingestGroup(client, indices.map { snapshot(it, 2) }, batchSize, updateLatency)
                                indices.filter { it % 20 == 0 }.forEach { index ->
                                    ingest(client, snapshot(index, 2), "DUPLICATE", replayLatency)
                                    ingest(client, snapshot(index, 1, replay = true), "STALE", replayLatency)
                                }
                            }
                        }
                    }
                    writers.forEach { it.get() }
                } finally {
                    updating.set(false)
                    reader.get()
                }
            }
            report("concurrent-update", updateLatency, updateStarted)
            report("duplicate-stale", replayLatency, updateStarted)
            report("conditional-get", readLatency, updateStarted)
            println(
                "LOAD locks=${lockTimer.count() - previousLockCount} " +
                    "lockTotalMs=${lockTimer.totalTime(TimeUnit.MILLISECONDS) - previousLockTime} " +
                    "lockMaxMs=${lockTimer.max(TimeUnit.MILLISECONDS)} " +
                    "get200=${readStatuses.count { it == 200 }} get304=${readStatuses.count { it == 304 }}",
            )

            val finalFeed = send(client, feedPath)
            assertThat(finalFeed.statusCode()).isEqualTo(200)
            val events = finalFeed.body().toByteArray().parseIcalendar().events()
            assertThat(events).hasSize(itemCount)
            val byUid = events.associateBy { it.requiredPropertyValue(Property.UID) }
            assertThat(byUid).hasSize(itemCount)
            (1..itemCount).forEach { index ->
                val event = byUid.getValue("${UUID(0, index.toLong())}@cal.baton")
                assertThat(event.requiredPropertyValue(Property.SEQUENCE)).isEqualTo("2")
                assertThat(event.requiredPropertyValue(Property.STATUS))
                    .isEqualTo(if (index % 2 == 0) "CANCELLED" else "CONFIRMED")
            }
            val finalEtag = finalFeed.headers().firstValue("ETag").orElseThrow()
            assertThat(projectionService.rebuild(SEASON_ID).etag).isEqualTo(finalEtag)
            val unchanged = send(client, feedPath, etag = finalEtag)
            assertThat(unchanged.statusCode()).isEqualTo(304)
            assertThat(unchanged.body()).isEmpty()
        }
    }

    private fun ingestGroup(client: HttpClient, payloads: List<String>, batchSize: Int, latency: ConcurrentLinkedQueue<Double>) {
        if (batchSize == 1) {
            ingest(client, payloads.single(), "APPLIED", latency)
            return
        }
        val started = System.nanoTime()
        val response = send(client, "/internal/api/v1/schedule-snapshots/batch", """{"snapshots":[${payloads.joinToString(",")}]}""")
        latency.add(elapsedMillis(started))
        assertThat(response.statusCode()).isEqualTo(200)
        val results = JSON.readTree(response.body())["results"].values()
        assertThat(results).hasSize(payloads.size)
        assertThat(results.map { it["result"].asString() }).containsOnly("APPLIED")
    }

    private fun ingest(client: HttpClient, payload: String, result: String, latency: ConcurrentLinkedQueue<Double>) {
        val started = System.nanoTime()
        val response = send(client, "/internal/api/v1/schedule-snapshots", payload)
        latency.add(elapsedMillis(started))
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(JSON.readTree(response.body())["result"].asString()).isEqualTo(result)
    }

    private fun send(client: HttpClient, path: String, body: String? = null, etag: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(30))
        if (body != null) {
            request.header("Authorization", "Bearer ingestion-load-test-token-that-is-long-enough")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        }
        etag?.let { request.header("If-None-Match", it) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun snapshot(index: Int, revision: Int, replay: Boolean = false): String {
        val document = JSON.readTree(TEMPLATE) as ObjectNode
        document.put("eventId", UUID(if (replay) 9 else revision + 1L, index.toLong()).toString())
        document.put("sourceItemId", UUID(0, index.toLong()).toString())
        document.put("seasonId", SEASON_ID.toString())
        document.put("revision", revision)
        document.put("status", if (revision > 0 && index % 2 == 0) "CANCELLED" else "ACTIVE")
        document.put("sourceUpdatedAt", Instant.parse("2026-08-11T01:00:00Z").plusSeconds(revision.toLong()).toString())
        return JSON.writeValueAsString(document)
    }

    private fun report(phase: String, latency: Collection<Double>, started: Long) {
        val sorted = latency.sorted()
        if (sorted.isEmpty()) return
        fun percentile(fraction: Double) = sorted[ceil(sorted.size * fraction).toInt() - 1]
        val seconds = elapsedMillis(started) / 1000
        println(String.format(
            Locale.ROOT,
            "LOAD phase=%s count=%d elapsedSeconds=%.3f requestsPerSecond=%.2f p50Ms=%.3f p95Ms=%.3f maxMs=%.3f",
            phase, sorted.size, seconds, sorted.size / seconds, percentile(0.50), percentile(0.95), sorted.last(),
        ))
    }

    private fun elapsedMillis(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("f5316f93-d49e-4230-b1d0-9e9c2d079819")
        val JSON = JsonMapper()
        val TEMPLATE = Path("contracts/examples/schedule-snapshot.utc-active.json").readText()
    }
}
