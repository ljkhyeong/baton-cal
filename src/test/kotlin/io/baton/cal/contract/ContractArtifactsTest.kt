package io.baton.cal.contract

import com.networknt.schema.InputFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import java.nio.file.Path
import java.time.Instant

class ContractArtifactsTest {

    @Test
    fun `모든 JSON 스키마와 예시는 명시된 매핑으로 검증된다`() {
        val actualSchemaFiles = jsonFileNames(ContractSchemaSupport.schemaDirectory)
        val actualExampleFiles = jsonFileNames(EXAMPLE_DIRECTORY)
        val mappedExampleFiles = EXAMPLES_BY_SCHEMA.values.flatten()

        assertThat(EXAMPLES_BY_SCHEMA.keys)
            .containsExactlyInAnyOrderElementsOf(actualSchemaFiles)
        assertThat(mappedExampleFiles)
            .containsExactlyInAnyOrderElementsOf(actualExampleFiles)

        EXAMPLES_BY_SCHEMA.forEach { (schemaFile, exampleFiles) ->
            val schemaDocument = JSON_MAPPER.readTree(
                ContractSchemaSupport.schemaDirectory.resolve(schemaFile).readText(),
            )
            assertThat(schemaDocument["\$schema"].asString())
                .describedAs("%s dialect", schemaFile)
                .isEqualTo(DRAFT_2020_12_DIALECT)

            exampleFiles.forEach { exampleFile ->
                ContractSchemaSupport.assertValid(
                    schemaFile,
                    EXAMPLE_DIRECTORY.resolve(exampleFile).readText(),
                    "$schemaFile 스키마로 $exampleFile 예시 검증",
                )
            }
        }
    }

    @Test
    fun `format 키워드는 assertion으로 평가된다`() {
        val errors = ContractSchemaSupport.loadSchema("subscription-create.v1.schema.json").validate(
            """{"seasonId":"UUID가-아님"}""",
            InputFormat.JSON,
        )

        assertThat(errors.map { it.keyword }).contains("format")
    }

    @Test
    fun `시간 스키마는 윤초와 9자리 초과 소수를 거부한다`() {
        val schema = ContractSchemaSupport.loadSchema("schedule-snapshot.v1.schema.json")
        val validUtcExample = EXAMPLE_DIRECTORY.resolve("schedule-snapshot.utc-active.json").readText()
        val validZonedExample = EXAMPLE_DIRECTORY.resolve("schedule-snapshot.zoned-active-r0.json").readText()
        val invalidExamples = linkedMapOf(
            "절대 시각 윤초" to validUtcExample.replace(
                "2026-08-11T01:00:05Z",
                "2026-08-11T01:00:60Z",
            ),
            "현지 시각 윤초" to validZonedExample.replace(
                "2026-08-31T22:30:00",
                "2026-08-31T22:30:60",
            ),
            "10자리 소수" to validUtcExample.replace(
                "2026-08-11T01:00:05Z",
                "2026-08-11T01:00:05.1234567890Z",
            ),
        )

        invalidExamples.forEach { (caseName, example) ->
            assertThat(schema.validate(example, InputFormat.JSON))
                .describedAs("%s", caseName)
                .isNotEmpty()
        }
    }

    @Test
    fun `TEXT 스키마는 정상 Unicode를 허용하고 금지 문자를 문자열 끝까지 거부한다`() {
        val schema = ContractSchemaSupport.loadSchema("schedule-snapshot.v1.schema.json")
        val validExample = EXAMPLE_DIRECTORY.resolve("schedule-snapshot.utc-active.json").readText()
        val supportedText = validExample.replace(
            "ROUND 1 운영",
            "줄 바꿈\\n탭\\t과 😀",
        )
        val trailingCarriageReturn = validExample.replace(
            "ROUND 1 운영",
            "ROUND 1 운영\\r",
        )
        val unpairedSurrogate = validExample.replace(
            "ROUND 1 운영",
            "ROUND 1 운영\\uD800",
        )

        assertThat(schema.validate(supportedText, InputFormat.JSON)).isEmpty()
        assertThat(schema.validate(trailingCarriageReturn, InputFormat.JSON)).isNotEmpty()
        assertThat(schema.validate(unpairedSurrogate, InputFormat.JSON)).isNotEmpty()
    }

    @Test
    fun `시간대 일정 생명주기 예시는 같은 항목과 시즌에서 개정 번호와 원본 시각이 전진한다`() {
        val snapshots = ZONED_LIFECYCLE_EXAMPLES.map(::readExample)

        assertThat(snapshots.map { it["eventId"].asString() }).doesNotHaveDuplicates()
        assertThat(snapshots.map { it["sourceItemId"].asString() }).containsOnly(ZONED_SOURCE_ITEM_ID)
        assertThat(snapshots.map { it["seasonId"].asString() }).containsOnly(SEASON_ID)
        assertThat(snapshots.map { it["revision"].intValue() }).containsExactly(0, 2, 3, 4)
        assertThat(snapshots.map { it["status"].asString() })
            .containsExactly("ACTIVE", "ACTIVE", "CANCELLED", "ACTIVE")
        assertThat(snapshots.map { Instant.parse(it["sourceUpdatedAt"].asString()) })
            .doesNotHaveDuplicates()
            .isSorted()
    }

    private fun readExample(fileName: String): JsonNode =
        JSON_MAPPER.readTree(EXAMPLE_DIRECTORY.resolve(fileName).readText())

    private fun jsonFileNames(directory: Path): Set<String> = directory
        .listDirectoryEntries("*.json")
        .filter { it.isRegularFile() }
        .mapTo(mutableSetOf()) { it.name }

    companion object {
        private val EXAMPLE_DIRECTORY = Path.of("contracts/examples")

        private val EXAMPLES_BY_SCHEMA = linkedMapOf(
            "api-error.v1.schema.json" to listOf(
                "api-error.recovery-in-progress.json",
                "api-error.service-busy.json",
                "api-error.source-revision-conflict.json",
            ),
            "calendar-item-status.v1.schema.json" to listOf(
                "calendar-item-status.cancelled.json",
            ),
            "projection-rebuild-result.v1.schema.json" to listOf(
                "projection-rebuild-result.json",
            ),
            "schedule-snapshot-result.v1.schema.json" to listOf(
                "schedule-snapshot-result.applied.json",
                "schedule-snapshot-result.duplicate.json",
                "schedule-snapshot-result.stale.json",
            ),
            "schedule-snapshot.v1.schema.json" to listOf(
                "schedule-snapshot.all-day-active.json",
                "schedule-snapshot.utc-active.json",
                "schedule-snapshot.utc-point-active.json",
                "schedule-snapshot.zoned-active-r0.json",
                "schedule-snapshot.zoned-active-r2.json",
                "schedule-snapshot.zoned-cancelled.json",
                "schedule-snapshot.zoned-point-active.json",
                "schedule-snapshot.zoned-reactivated.json",
            ),
            "season-calendar-metadata.v1.schema.json" to listOf(
                "season-calendar-metadata.r0.json",
                "season-calendar-metadata.r2.json",
            ),
            "season-calendar-metadata-result.v1.schema.json" to listOf(
                "season-calendar-metadata-result.json",
            ),
            "subscription-create.v1.schema.json" to listOf(
                "subscription-create.json",
            ),
            "subscription-credential.v1.schema.json" to listOf(
                "subscription-credential.json",
            ),
            "subscription-status.v1.schema.json" to listOf(
                "subscription-status.generation-mismatch.json",
            ),
        )

        private val ZONED_LIFECYCLE_EXAMPLES = listOf(
            "schedule-snapshot.zoned-active-r0.json",
            "schedule-snapshot.zoned-active-r2.json",
            "schedule-snapshot.zoned-cancelled.json",
            "schedule-snapshot.zoned-reactivated.json",
        )

        private const val ZONED_SOURCE_ITEM_ID = "b8ca471a-b228-42fa-8d41-28f05ee90d40"
        private const val SEASON_ID = "f5316f93-d49e-4230-b1d0-9e9c2d079819"
        private const val DRAFT_2020_12_DIALECT = "https://json-schema.org/draft/2020-12/schema"

        private val JSON_MAPPER = JsonMapper()
    }
}
