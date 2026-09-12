package io.baton.cal.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import java.net.URI
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText

@ExtendWith(OutputCaptureExtension::class)
class ProductionDatasourceConfigurationTest {
    @TempDir
    lateinit var secrets: Path

    @Test
    fun `운영 필수 설정은 외부에서 받고 관리 포트를 분리한다`() {
        val properties = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-prod.yml"))
        }.getObject()

        assertThat(properties)
            .containsEntry("spring.datasource.url", "${'$'}{DATABASE_URL}")
            .containsEntry("spring.datasource.username", "${'$'}{DATABASE_USERNAME}")
            .containsEntry("spring.datasource.password", "${'$'}{DATABASE_PASSWORD}")
            .containsEntry("baton.cal.public-base-url", "${'$'}{BATON_CAL_PUBLIC_BASE_URL}")
            .containsEntry(
                "baton.cal.subscription-generation",
                "${'$'}{BATON_CAL_SUBSCRIPTION_GENERATION}",
            )
            .containsEntry("management.server.port", "${'$'}{MANAGEMENT_SERVER_PORT:8081}")
    }

    @Test
    fun `운영 설정은 Secret 파일과 외부 설정을 함께 읽는다`(output: CapturedOutput) {
        writeSecrets()

        application().run(*arguments()).use { context ->
            val datasource = context.getBean(DataSourceProperties::class.java)
            val cal = context.getBean(CalProperties::class.java)
            assertThat(datasource.url).isEqualTo("jdbc:postgresql://postgres:5432/baton_cal")
            assertThat(datasource.username).isEqualTo("baton_cal")
            assertThat(datasource.password).isEqualTo(DATABASE_SECRET)
            assertThat(cal.internalToken).isEqualTo(CURRENT_TOKEN)
            assertThat(cal.previousInternalToken).isEqualTo(PREVIOUS_TOKEN)
            assertThat(cal.publicBaseUrl).isEqualTo(URI.create("https://cal.b4ton.com"))
            assertThat(cal.subscriptionGeneration).isEqualTo(UUID.fromString(GENERATION))
            assertThat(context.environment.getProperty("management.server.port")).isEqualTo("8081")
        }
        assertThat(output.all).doesNotContain(DATABASE_SECRET, CURRENT_TOKEN, PREVIOUS_TOKEN)
    }

    @ParameterizedTest
    @ValueSource(strings = ["BATON_CAL_INTERNAL_TOKEN", "baton.cal.previous-internal-token"])
    fun `파일의 잘못된 토큰은 시작을 차단하고 로그에 노출하지 않는다`(
        filename: String,
        output: CapturedOutput,
    ) {
        writeSecrets()
        val rejectedSecret = "short-secret-marker"
        secrets.resolve(filename).writeText(rejectedSecret)

        assertThatThrownBy { application().run(*arguments()).close() }
            .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
        assertThat(output.all)
            .doesNotContain(rejectedSecret, DATABASE_SECRET, CURRENT_TOKEN, PREVIOUS_TOKEN)
    }

    @Test
    fun `Secret 디렉터리가 없으면 운영 시작을 차단한다`() {
        assertThatThrownBy { application().run(*arguments(secrets.resolve("missing"))).close() }
            .isInstanceOf(ConfigDataResourceNotFoundException::class.java)
    }

    private fun writeSecrets() {
        secrets.resolve("DATABASE_PASSWORD").writeText(DATABASE_SECRET)
        secrets.resolve("BATON_CAL_INTERNAL_TOKEN").writeText(CURRENT_TOKEN)
        secrets.resolve("baton.cal.previous-internal-token").writeText(PREVIOUS_TOKEN)
    }

    private fun arguments(directory: Path = secrets) = arrayOf(
        "--spring.profiles.active=prod",
        "--spring.config.import=configtree:$directory/",
        "--DATABASE_URL=jdbc:postgresql://postgres:5432/baton_cal",
        "--DATABASE_USERNAME=baton_cal",
        "--BATON_CAL_PUBLIC_BASE_URL=https://cal.b4ton.com",
        "--BATON_CAL_SUBSCRIPTION_GENERATION=$GENERATION",
    )

    private fun application() = SpringApplication(SecretFilesApplication::class.java).apply {
        setWebApplicationType(WebApplicationType.NONE)
        setLogStartupInfo(false)
        setRegisterShutdownHook(false)
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CalProperties::class, DataSourceProperties::class)
    @Import(ProductionConfiguration::class)
    private class SecretFilesApplication

    companion object {
        private const val DATABASE_SECRET = "file-only-database-secret"
        private const val CURRENT_TOKEN = "file-only-current-token-that-is-long-enough"
        private const val PREVIOUS_TOKEN = "file-only-previous-token-that-is-long-enough"
        private const val GENERATION = "60000000-0000-0000-0000-000000000001"
    }
}
