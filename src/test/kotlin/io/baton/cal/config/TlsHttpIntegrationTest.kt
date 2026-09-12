package io.baton.cal.config

import com.jayway.jsonpath.JsonPath
import io.baton.cal.BatonCalApplication
import io.baton.cal.support.PostgreSqlTestContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.io.path.inputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.testcontainers.postgresql.PostgreSQLContainer

@ExtendWith(OutputCaptureExtension::class)
class TlsHttpIntegrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `HTTPS에서 인증과 구독을 처리하고 관리 HTTP 포트를 분리한다`(output: CapturedOutput) {
        val sslContext = createCertificate()
        directory.resolve("BATON_CAL_INTERNAL_TOKEN").writeText(INTERNAL_TOKEN)
        PostgreSQLContainer(PostgreSqlTestContainer.IMAGE).use { database ->
            database.start()
            directory.resolve("DATABASE_PASSWORD").writeText(database.password)
            SpringApplication(BatonCalApplication::class.java).apply {
                setRegisterShutdownHook(false)
            }.run(
                "--spring.profiles.active=prod,tls",
                "--spring.config.import=configtree:$directory/",
                "--DATABASE_URL=${database.jdbcUrl}",
                "--DATABASE_USERNAME=${database.username}",
                "--BATON_CAL_PUBLIC_BASE_URL=https://cal.b4ton.com",
                "--BATON_CAL_SUBSCRIPTION_GENERATION=60000000-0000-0000-0000-000000000002",
                "--BATON_CAL_TLS_CERTIFICATE=${directory.resolve("tls.crt").toUri()}",
                "--BATON_CAL_TLS_PRIVATE_KEY=${directory.resolve("tls.key").toUri()}",
                "--SERVER_PORT=0",
                "--MANAGEMENT_SERVER_PORT=0",
            ).use { context ->
                val baseUrl = "https://localhost:${context.environment.getRequiredProperty("local.server.port")}"
                val managementUrl = "http://localhost:${context.environment.getRequiredProperty("local.management.port")}"
                HttpClient.newBuilder().sslContext(sslContext).connectTimeout(Duration.ofSeconds(5)).build().use { client ->
                    fun request(url: String, method: String = "GET", body: String? = null, authorized: Boolean = false): HttpResponse<String> {
                        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                        if (authorized) builder.header("Authorization", "Bearer $INTERNAL_TOKEN")
                        if (body != null) builder.header("Content-Type", "application/json")
                        return client.send(
                            builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString)
                                ?: HttpRequest.BodyPublishers.noBody()).build(),
                            HttpResponse.BodyHandlers.ofString(),
                        )
                    }

                    val subscriptionUrl = "$baseUrl/internal/api/v1/subscriptions/cccccccc-cccc-cccc-cccc-cccccccccccc"
                    assertThat(request(subscriptionUrl).statusCode()).isEqualTo(401)
                    val created = request(subscriptionUrl, "PUT", """{"seasonId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}""", true)
                    assertThat(created.statusCode()).isEqualTo(201)
                    val feedUrl = URI.create(JsonPath.read<String>(created.body(), "$.feedUrl"))
                    assertThat(feedUrl.host).isEqualTo("cal.b4ton.com")
                    val feed = request("$baseUrl${feedUrl.rawPath}")
                    assertThat(feed.statusCode()).isEqualTo(200)
                    assertThat(feed.body()).contains("BEGIN:VCALENDAR")
                    assertThat(request(subscriptionUrl, "DELETE", authorized = true).statusCode()).isEqualTo(204)
                    assertThat(request("$baseUrl${feedUrl.rawPath}").statusCode()).isEqualTo(404)
                    assertThat(request("$managementUrl/actuator/health/readiness").statusCode()).isEqualTo(200)
                    for (path in listOf("/livez", "/readyz")) {
                        val health = request("$baseUrl$path")
                        assertThat(health.statusCode()).isEqualTo(200)
                        assertThat(health.body()).isEqualTo("""{"status":"UP"}""")
                    }
                    AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC)
                    assertThat(request("$baseUrl/readyz").statusCode()).isEqualTo(503)
                    assertThat(request("$managementUrl/actuator/health/readiness").statusCode()).isEqualTo(503)
                    assertThat(request("$baseUrl/livez").statusCode()).isEqualTo(200)
                    AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC)
                    assertThat(request("$baseUrl/readyz").statusCode()).isEqualTo(200)
                    val metrics = request("$managementUrl/actuator/prometheus")
                    assertThat(metrics.statusCode()).isEqualTo(200)
                    assertThat(metrics.body()).doesNotContain(feedUrl.rawPath, INTERNAL_TOKEN)
                    assertThat(request("$baseUrl/actuator/prometheus").statusCode()).isEqualTo(404)
                    assertThat(output.all).doesNotContain(INTERNAL_TOKEN, feedUrl.rawPath, directory.resolve("tls.key").readText())
                }
            }
        }
    }

    private fun createCertificate(): SSLContext {
        val password = "local-test-only".toCharArray()
        val storePath = directory.resolve("test.p12")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "cal", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1",
            "-storetype", "PKCS12", "-keystore", storePath.toString(), "-storepass", String(password),
        ).redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start()
        assertThat(process.waitFor()).isZero()
        val store = KeyStore.getInstance("PKCS12").apply { storePath.inputStream().use { load(it, password) } }
        fun pem(label: String, bytes: ByteArray) =
            "-----BEGIN $label-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(bytes)}\n-----END $label-----\n"
        directory.resolve("tls.crt").writeText(pem("CERTIFICATE", store.getCertificate("cal").encoded))
        directory.resolve("tls.key").writeText(pem("PRIVATE KEY", store.getKey("cal", password).encoded))
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
    }

    companion object {
        private const val INTERNAL_TOKEN = "local-tls-test-internal-token-long-enough"
    }
}
