package io.baton.cal.subscription

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SubscriptionTokenCodecTest {
    private val codec = SubscriptionTokenCodec()

    @Test
    fun `구독 토큰 해시는 기존 저장 형식과 호환된다`() {
        assertThat(codec.hash("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
            .isEqualTo("0f007385b6f9d4b7eeb2748605afe1a984a0a3bfa3f014d09e2a784ce9e5cd1a")
    }
}
