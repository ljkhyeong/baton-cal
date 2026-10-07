package io.baton.cal.support

import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.postgresql.PostgreSQLContainer

/** 통합 테스트가 공유하는 PostgreSQL이다. 시작과 연결 정보 등록은 Spring Boot Testcontainers가 맡는다. */
object PostgreSqlTestContainer {
    @ServiceConnection
    @JvmField
    val postgres = PostgreSQLContainer("postgres:18.6-alpine")
}
