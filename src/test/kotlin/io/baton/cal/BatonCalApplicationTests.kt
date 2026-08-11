package io.baton.cal

import io.baton.cal.config.CalProperties
import java.time.Clock
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@Testcontainers
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=0123456789abcdef0123456789abcdef",
    ],
)
class BatonCalApplicationTests
    @Autowired
    constructor(
        private val clock: Clock,
        private val properties: CalProperties,
    ) {
        @Test
        fun contextLoads() {
            assertThat(clock.zone).isEqualTo(ZoneOffset.UTC)
            assertThat(properties.internalToken).hasSizeGreaterThanOrEqualTo(32)
            assertThat(properties.publicBaseUrl.toString()).isEqualTo("http://localhost:8080")
        }

        companion object {
            @Container
            @ServiceConnection
            @JvmField
            val postgres = PostgreSQLContainer("postgres:18.4-alpine")
        }
    }
