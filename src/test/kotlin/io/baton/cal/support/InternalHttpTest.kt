package io.baton.cal.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.jdbc.Sql

/**
 * 내부 API와 공개 피드를 MockMvc로 검증하는 테스트 설정이다. 이 어노테이션을 쓰는 클래스는 Spring 테스트
 * 컨텍스트와 DB 연결 풀을 공유한다. 다른 속성이나 빈 교체가 필요하면 별도 설정을 쓴다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ImportTestcontainers(PostgreSqlTestContainer::class)
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "baton.cal.internal-token=$TEST_INTERNAL_TOKEN",
        "baton.cal.previous-internal-token=$TEST_PREVIOUS_INTERNAL_TOKEN",
        "baton.cal.public-base-url=$TEST_PUBLIC_BASE_URL",
    ],
)
@Sql("/reset-database.sql")
annotation class InternalHttpTest

/** [InternalHttpTest]에 런타임 복구 모드를 켠 설정이다. 이 어노테이션을 쓰는 클래스끼리 컨텍스트를 공유한다. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@InternalHttpTest
@TestPropertySource(properties = ["baton.cal.recovery-mode=true"])
annotation class RecoveryModeInternalHttpTest
