package io.baton.cal.contract

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal object ContractSchemaSupport {

    val schemaDirectory: Path = Path.of("contracts/schemas")

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
