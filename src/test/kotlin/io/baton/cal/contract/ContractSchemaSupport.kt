package io.baton.cal.contract

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

internal object ContractSchemaSupport {

    val schemaDirectory: Path = Path.of("contracts/schemas")

    fun loadSchema(fileName: String): Schema = schemas.computeIfAbsent(fileName) {
        Files.newInputStream(schemaDirectory.resolve(it))
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
        builder.schemaRegistryConfig(
            SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(true)
                .build(),
        )
    }

    private val schemas = ConcurrentHashMap<String, Schema>()
}
