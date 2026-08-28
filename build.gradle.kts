import org.gradle.api.tasks.bundling.Zip

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.baton"
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
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
    implementation("org.mnode.ical4j:ical4j:4.2.5")

    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
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
        excludeTags("load")
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

val contractsVersion = providers
    .fileContents(layout.projectDirectory.file("contracts/VERSION"))
    .asText
    .map { it.trim() }

tasks.register<Zip>("contractsZip") {
    group = "distribution"
    description = "BATON CAL 계약 팩 ZIP을 생성합니다."
    archiveBaseName.set("baton-cal-contracts")
    archiveVersion.set(contractsVersion)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(layout.projectDirectory.dir("contracts")) {
        into("contracts")
    }
    from(layout.projectDirectory.file("docs/PRD/0002_mvp-contract/spec.md")) {
        into("docs/PRD/0002_mvp-contract")
    }
}
