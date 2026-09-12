package io.baton.cal.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import io.baton.architecture.fixtures.domain.ConstructingService
import io.baton.architecture.fixtures.domain.InfrastructureDomain
import io.baton.architecture.fixtures.domain.InjectedService
import io.baton.architecture.fixtures.domain.JdbcService
import io.baton.architecture.fixtures.domain.RepositoryController
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("architecture")
class ArchitectureRulesTest {
    @Test
    fun `금지한 의존성을 실제 바이트코드에서 탐지한다`() {
        listOf(
            ArchitectureRules.controllerRule to RepositoryController::class.java,
            ArchitectureRules.domainRule to InfrastructureDomain::class.java,
            ArchitectureRules.serviceJdbcRule to JdbcService::class.java,
            ArchitectureRules.serviceConstructionRule to ConstructingService::class.java,
        ).forEach { (rule, fixture) ->
            assertTrue(rule.evaluate(ClassFileImporter().importClasses(fixture)).hasViolation(), fixture.name)
        }
    }

    @Test
    fun `Spring Repository 주입은 허용한다`() {
        val classes = ClassFileImporter().importClasses(InjectedService::class.java)
        ArchitectureRules.serviceJdbcRule.check(classes)
        ArchitectureRules.serviceConstructionRule.check(classes)
    }
}
