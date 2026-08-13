package io.baton.cal.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.net.URI

class CalPropertiesTest {
    @Test
    fun `내부 토큰은 오류와 문자열 표현에 노출되지 않는다`() {
        val secret = "secret-internal-token-that-is-long-enough"
        assertThat(CalProperties(secret, URI.create("https://calendar.example.test")).toString())
            .doesNotContain(secret)
        assertThatIllegalArgumentException()
            .isThrownBy { CalProperties("leaky-secret", URI.create("https://calendar.example.test")) }
            .withMessageNotContaining("leaky-secret")
    }

    @Test
    fun `공개 기준 URL은 호스트가 있는 계층형 HTTP URI여야 한다`() {
        val token = "secret-internal-token-that-is-long-enough"

        listOf("http:opaque", "http:///missing-host").forEach { invalidUrl ->
            assertThatIllegalArgumentException()
                .isThrownBy { CalProperties(token, URI.create(invalidUrl)) }
                .withMessageContaining("호스트")
        }
    }
}
