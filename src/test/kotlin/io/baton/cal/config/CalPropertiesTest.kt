package io.baton.cal.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.util.UUID

@ExtendWith(OutputCaptureExtension::class)
class CalPropertiesTest {
    @Test
    fun `내부 토큰은 오류와 문자열 표현에 노출되지 않는다`() {
        val currentSecret = "current-internal-token-that-is-long-enough"
        val previousSecret = "previous-internal-token-that-is-long-enough"
        val properties = CalProperties(
            internalToken = currentSecret,
            publicBaseUrl = URI.create("https://calendar.example.test"),
            previousInternalToken = previousSecret,
        )

        assertThat(properties.toString())
            .doesNotContain(currentSecret)
            .doesNotContain(previousSecret)
        assertThatIllegalArgumentException()
            .isThrownBy { CalProperties("leaky-secret", URI.create("https://calendar.example.test")) }
            .withMessageNotContaining("leaky-secret")
        assertThatIllegalArgumentException()
            .isThrownBy {
                CalProperties(
                    internalToken = currentSecret,
                    publicBaseUrl = URI.create("https://calendar.example.test"),
                    previousInternalToken = "leaky-previous-secret",
                )
            }
            .withMessageNotContaining("leaky-previous-secret")
        assertThatIllegalArgumentException()
            .isThrownBy {
                CalProperties(
                    internalToken = currentSecret,
                    publicBaseUrl = URI.create("https://calendar.example.test"),
                    previousInternalToken = currentSecret,
                )
            }
            .withMessageContaining("달라야")
    }

    @Test
    fun `내부 토큰은 RFC 6750 Bearer 자격 증명 형식을 따른다`() {
        listOf(
            "a".repeat(32),
            "Aa0-._~+/".repeat(4) + "==",
        ).forEach { token ->
            assertThatCode { CalProperties(token) }.doesNotThrowAnyException()
        }

        listOf(
            " ".repeat(32),
            "a".repeat(31) + " ",
            "a".repeat(31) + ":",
            "a".repeat(16) + "=" + "a".repeat(16),
        ).forEach { token ->
            assertThatIllegalArgumentException()
                .isThrownBy { CalProperties(token) }
                .withMessageNotContaining(token)
        }
    }

    @Test
    fun `공개 기준 URL은 계층형 HTTPS 또는 로컬 개발용 HTTP여야 한다`() {
        val token = "secret-internal-token-that-is-long-enough"

        listOf("http:opaque", "http:///missing-host").forEach { invalidUrl ->
            assertThatIllegalArgumentException()
                .isThrownBy { CalProperties(token, URI.create(invalidUrl)) }
                .withMessageContaining("호스트")
        }

        listOf(
            "http://localhost:8080",
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
            "HTTP://localhost:8080",
            "https://calendar.example.test",
            "HTTPS://calendar.example.test",
        ).forEach { validUrl ->
            assertThat(CalProperties(token, URI.create(validUrl)).publicBaseUrl)
                .isEqualTo(URI.create(validUrl))
        }

        assertThatIllegalArgumentException()
            .isThrownBy { CalProperties(token, URI.create("http://calendar.example.test")) }
            .withMessageContaining("HTTPS")
    }

    @Test
    fun `구독 세대는 기존 데이터용 초기값을 쓰고 NIL UUID는 거부한다`() {
        val token = "secret-internal-token-that-is-long-enough"
        val configuredGeneration = UUID.fromString("11111111-1111-1111-1111-111111111111")

        assertThat(CalProperties(token).subscriptionGeneration)
            .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        assertThat(
            CalProperties(
                internalToken = token,
                subscriptionGeneration = configuredGeneration,
            ).subscriptionGeneration,
        ).isEqualTo(configuredGeneration)
        assertThatIllegalArgumentException()
            .isThrownBy {
                CalProperties(
                    internalToken = token,
                    subscriptionGeneration = UUID(0, 0),
                )
            }
            .withMessageContaining("NIL UUID")
    }

    @Test
    fun `운영 프로필은 HTTPS 스킴의 대소문자를 구분하지 않고 HTTP를 거부한다`() {
        val token = "secret-internal-token-that-is-long-enough"

        assertThatCode {
            ProductionConfiguration(
                CalProperties(token, URI.create("HTTPS://calendar.example.test")),
            )
        }.doesNotThrowAnyException()

        assertThatIllegalArgumentException()
            .isThrownBy {
                ProductionConfiguration(
                    CalProperties(token, URI.create("http://localhost:8080")),
                )
            }
            .withMessageContaining("prod")
            .withMessageContaining("HTTPS")
    }

    @Test
    fun `설정 바인딩 실패 출력은 거부된 내부 토큰을 노출하지 않는다`(output: CapturedOutput) {
        val rejectedSecret = "leaky-short-secret"
        val application = SpringApplication(PropertiesBindingApplication::class.java).apply {
            setWebApplicationType(WebApplicationType.NONE)
            setLogStartupInfo(false)
            setRegisterShutdownHook(false)
        }

        assertThatThrownBy {
            application.run(
                "--baton.cal.internal-token=$rejectedSecret",
                "--baton.cal.public-base-url=https://calendar.example.test",
            )
        }.hasRootCauseInstanceOf(IllegalArgumentException::class.java)

        assertThat(output.all).doesNotContain(rejectedSecret)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CalProperties::class)
    private class PropertiesBindingApplication
}
