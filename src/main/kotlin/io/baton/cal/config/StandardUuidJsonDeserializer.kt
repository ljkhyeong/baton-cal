package io.baton.cal.config

import org.springframework.boot.jackson.JacksonComponent
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.jdk.UUIDDeserializer
import java.util.UUID

@JacksonComponent
class StandardUuidJsonDeserializer : UUIDDeserializer() {
    override fun _shouldTrim(): Boolean = false

    override fun _deserialize(value: String, context: DeserializationContext): UUID {
        return try {
            StandardUuid.parse(value)
        } catch (_: IllegalArgumentException) {
            context.reportInputMismatch(
                UUID::class.java,
                StandardUuid.ERROR_MESSAGE,
            )
        }
    }
}
