package io.baton.cal.config

import org.springframework.boot.jackson.JacksonComponent
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.jdk.UUIDDeserializer
import java.util.UUID

@JacksonComponent
class StandardUuidJsonDeserializer : UUIDDeserializer() {
    override fun _shouldTrim(): Boolean = false

    override fun _deserialize(value: String, context: DeserializationContext): UUID {
        if (value.length != STANDARD_UUID_LENGTH) {
            return context.reportInputMismatch(
                UUID::class.java,
                "UUID는 하이픈을 포함한 36자 표준 문자열이어야 합니다",
            )
        }
        return super._deserialize(value, context)
    }

    private companion object {
        const val STANDARD_UUID_LENGTH = 36
    }
}
