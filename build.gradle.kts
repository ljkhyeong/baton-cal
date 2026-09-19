import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import java.util.zip.ZipFile

abstract class VerifyContractsZip : DefaultTask() {

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val contractsDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val version = expectedVersion.get()
        val archive = archiveFile.get().asFile
        check(archive.name == "baton-cal-contracts-$version.zip") {
            "계약 ZIP 파일명이 contracts/VERSION과 다릅니다: ${archive.name}"
        }

        val contractsRoot = contractsDirectory.get().asFile
        val expectedFiles = contractsRoot
            .walkTopDown()
            .filter { it.isFile }
            .mapTo(linkedSetOf("LICENSE", "docs/PRD/0002_mvp-contract/spec.md")) {
                "contracts/${it.relativeTo(contractsRoot).invariantSeparatorsPath}"
            }

        ZipFile(archive).use { zipFile ->
            val packagedFiles = zipFile.entries()
                .asSequence()
                .filterNot { it.isDirectory }
                .mapTo(linkedSetOf()) { it.name }
            check(packagedFiles == expectedFiles) {
                val missingFiles = expectedFiles - packagedFiles
                val unexpectedFiles = packagedFiles - expectedFiles
                "계약 ZIP 파일 목록이 소스와 다릅니다: 누락=$missingFiles, 추가=$unexpectedFiles"
            }

            val versionEntry = checkNotNull(zipFile.getEntry("contracts/VERSION")) {
                "계약 ZIP에 contracts/VERSION이 없습니다."
            }
            val packagedVersion = zipFile.getInputStream(versionEntry)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText().trim() }
            check(packagedVersion == version) {
                "계약 ZIP 내부 버전이 contracts/VERSION과 다릅니다: $packagedVersion"
            }
        }
    }
}

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.baton"
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xemit-jvm-type-annotations")
    }
}

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.mnode.ical4j:ical4j:4.3.0")

    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
    testImplementation("com.networknt:json-schema-validator:3.0.6") {
        exclude(group = "tools.jackson.dataformat", module = "jackson-dataformat-yaml")
    }
    testImplementation("io.micrometer:micrometer-observation-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("load", "ingestion-load", "architecture")
    }
}

tasks.register<Test>("architectureTest") {
    group = "verification"
    description = "Controller·도메인·Service의 의존성 규칙을 검증합니다."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("architecture") }
}

tasks.register<Exec>("feedbackLoopTest") {
    group = "verification"
    description = "파일 검사와 종료 검사 스크립트를 검증합니다."
    commandLine("python3", "-B", "-m", "unittest", "discover", "-s", "scripts/tests")
    inputs.files("scripts/agent_feedback.py", fileTree("scripts/tests") { include("*.py") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("pythonVersion", providers.exec { commandLine("python3", "--version") }.standardOutput.asText)
    val result = layout.buildDirectory.file("agent-feedback-tests/passed")
    outputs.file(result)
    doLast {
        result.get().asFile.apply { parentFile.mkdirs() }.writeText("passed\n")
    }
}

tasks.register<Test>("projectionLoadTest") {
    group = "verification"
    description = "시즌 항목 수에 따른 전체 투영 재구축 시간을 측정합니다."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("load")
    }
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("ingestionLoadTest") {
    group = "verification"
    description = "실제 HTTP 수신과 동시 조건부 조회의 처리량 및 지연을 측정합니다."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("ingestion-load") }
    systemProperty("baton.cal.load.item-count", providers.gradleProperty("loadItemCount").getOrElse("1000"))
    systemProperty("baton.cal.load.batch-size", providers.gradleProperty("loadBatchSize").getOrElse("1"))
    shouldRunAfter(tasks.test)
}

val contractsVersion = providers
    .fileContents(layout.projectDirectory.file("contracts/VERSION"))
    .asText
    .map { it.trim() }

val contractsSourceDirectory = layout.projectDirectory.dir("contracts")
val contractSpecification = layout.projectDirectory.file("docs/PRD/0002_mvp-contract/spec.md")

val contractsZip = tasks.register<Zip>("contractsZip") {
    group = "distribution"
    description = "BATON CAL 계약 팩 ZIP을 생성합니다."
    archiveBaseName.set("baton-cal-contracts")
    archiveVersion.set(contractsVersion)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    dirPermissions {
        unix("rwxr-xr-x")
    }
    filePermissions {
        unix("rw-r--r--")
    }

    from(layout.projectDirectory.file("LICENSE"))
    from(contractsSourceDirectory) {
        into("contracts")
    }
    from(contractSpecification) {
        into("docs/PRD/0002_mvp-contract")
    }
}

val verifyContractsZip = tasks.register<VerifyContractsZip>("verifyContractsZip") {
    group = "verification"
    description = "계약 팩 ZIP의 파일명, 내부 버전과 포함 파일을 검증합니다."
    dependsOn(contractsZip)
    expectedVersion.set(contractsVersion)
    archiveFile.set(contractsZip.flatMap { it.archiveFile })
    contractsDirectory.set(contractsSourceDirectory)
}

tasks.named("check") {
    dependsOn(verifyContractsZip, "architectureTest", "feedbackLoopTest")
}
