package io.baton.cal.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaConstructorCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.function.Executable
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service

internal object ArchitectureRules {
    private val database = DescribedPredicate.describe<JavaClass>("DB 접근 클래스") {
        it.simpleName.substringBefore('$').endsWith("Repository") ||
            it.isAnnotatedWith(Repository::class.java) || it.isMetaAnnotatedWith(Repository::class.java) ||
            it.packageName.startsWith("org.springframework.jdbc") ||
            it.packageName == "java.sql" || it.packageName == "javax.sql"
    }

    private val controllers = DescribedPredicate.describe<JavaClass>("Controller") {
        it.isMetaAnnotatedWith(Controller::class.java) || it.isAnnotatedWith(Controller::class.java)
    }

    // 현재 기능별 패키지에서 실행 계층만 제외한다. 새 도메인 클래스도 자동으로 검사한다.
    private val domain = DescribedPredicate.describe<JavaClass>("일정·스냅샷·복구 도메인") {
        val root = it.name.substringBefore('$').removeSuffix("Kt")
        it.packageName.contains(".domain") ||
            (setOf("io.baton.cal.calendar", "io.baton.cal.snapshot", "io.baton.cal.recovery")
                .any { pkg -> it.packageName == pkg || it.packageName.startsWith("$pkg.") } &&
                root !in setOf(
                    "io.baton.cal.calendar.IcsCalendarRenderer",
                    "io.baton.cal.snapshot.SnapshotIngestionService",
                    "io.baton.cal.recovery.RecoveryManifestService",
                ))
    }

    private val infrastructure = database.or(DescribedPredicate.describe("웹·설정·Spring 실행 계층") {
        it.packageName.startsWith("io.baton.cal.persistence") ||
            it.packageName.startsWith("io.baton.cal.web") ||
            it.packageName.startsWith("io.baton.cal.config") ||
            it.packageName.startsWith("org.springframework") ||
            it.isAnnotatedWith(Component::class.java) || it.isMetaAnnotatedWith(Component::class.java)
    })

    val controllerRule = noClasses().that(controllers).should().dependOnClassesThat(database)
        .because("Controller는 Service를 통해 DB에 접근해야 한다")
    val domainRule = noClasses().that(domain).should().dependOnClassesThat(infrastructure)
        .because("도메인은 웹·DB·Spring 실행 계층에 의존하지 않는다")
    val serviceJdbcRule = noClasses().that().areAnnotatedWith(Service::class.java)
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
        .because("Service의 SQL 처리는 Repository에 둔다")
    val serviceConstructionRule = noClasses().that().areAnnotatedWith(Service::class.java)
        .should().callConstructorWhere(DescribedPredicate.describe<JavaConstructorCall>("Repository 직접 생성") {
            database.test(it.targetOwner)
        }).because("Service는 Spring이 관리하는 Repository를 주입받는다")

    val all = listOf(controllerRule, domainRule, serviceJdbcRule, serviceConstructionRule)
}

@Tag("architecture")
class ArchitectureTest {
    @Test
    fun `운영 코드의 계층 의존성을 검사한다`() {
        val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.baton.cal")
        assertAll(ArchitectureRules.all.map { rule -> Executable { rule.check(classes) } })
    }
}
