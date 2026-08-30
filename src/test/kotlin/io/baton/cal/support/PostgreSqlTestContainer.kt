package io.baton.cal.support

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.postgresql.PostgreSQLContainer

class PostgreSqlTestContainer {
    companion object {
        const val IMAGE = "postgres:18.6-alpine"

        @Container
        @ServiceConnection
        @JvmField
        val postgres = PostgreSQLContainer(IMAGE)
    }
}
