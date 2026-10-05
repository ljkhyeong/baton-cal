package io.baton.cal.contract

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.springframework.test.web.servlet.ResultActions
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal object ContractSchemaSupport {

    val schemaDirectory: Path = Path.of("contracts/schemas")
    val exampleDirectory: Path = Path.of("contracts/examples")

    fun loadSchema(fileName: String): Schema = schemas.computeIfAbsent(fileName) {
        schemaDirectory.resolve(it).inputStream()
            .use(schemaRegistry::getSchema)
            .also(Schema::initializeValidators)
    }

    fun assertValid(
        schemaFileName: String,
        json: String,
        description: String,
    ) {
        val errors = loadSchema(schemaFileName).validate(json, InputFormat.JSON)

        assertThat(errors)
            .describedAs("%s (%s)", description, schemaFileName)
            .isEmpty()
    }

    private val schemaRegistry = SchemaRegistry.withDefaultDialect(
        SpecificationVersion.DRAFT_2020_12,
    ) { builder ->
        builder.schemas(schemaDirectory.listDirectoryEntries("*.json").associate {
            "https://cal.baton/contracts/schemas/${it.name}" to it.readText()
        })
        builder.schemaRegistryConfig(
            SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(true)
                .build(),
        )
    }

    private val schemas = ConcurrentHashMap<String, Schema>()
}

/** 응답 본문이 계약 스키마를 통과하는지 확인하고 본문을 반환한다. */
internal fun ResultActions.andReturnValid(schemaFileName: String, description: String): String =
    andReturn().response.contentAsString.also { ContractSchemaSupport.assertValid(schemaFileName, it, description) }

/** `contracts/examples`의 계약 예시 원문을 읽는다. */
internal fun contractExample(fileName: String): String =
    ContractSchemaSupport.exampleDirectory.resolve(fileName).readText()
