package io.baton.cal.support

import io.baton.cal.calendar.IcsCalendarRenderer
import io.baton.cal.persistence.AdvisoryLockRepository
import io.baton.cal.persistence.CalendarSubscriptionRepository
import io.baton.cal.projection.SeasonProjectionService
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.context.jdbc.Sql

/**
 * PostgreSQL과 MockMvc를 쓰는 통합 테스트 설정이다. 이 어노테이션을 쓰는 클래스는 Spring 테스트 컨텍스트와
 * DB 연결 풀을 공유한다. 동시성·실패 주입 테스트가 쓰는 빈은 실제 동작을 그대로 호출하는 스파이로 두고
 * 테스트마다 초기화된다. 다른 속성이나 빈 교체가 필요하면 별도 설정을 쓴다.
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
@MockitoSpyBean(
    types = [
        AdvisoryLockRepository::class,
        CalendarSubscriptionRepository::class,
        IcsCalendarRenderer::class,
        SeasonProjectionService::class,
    ],
)
annotation class CalIntegrationTest

/** [CalIntegrationTest]에 런타임 복구 모드를 켠 설정이다. 이 어노테이션을 쓰는 클래스끼리 컨텍스트를 공유한다. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@CalIntegrationTest
@TestPropertySource(properties = ["baton.cal.recovery-mode=true"])
annotation class RecoveryModeIntegrationTest
