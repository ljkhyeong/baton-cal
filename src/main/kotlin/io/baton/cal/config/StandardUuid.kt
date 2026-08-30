package io.baton.cal.config

import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component
import java.util.UUID

class StandardUuidPath(
    val value: UUID,
)

@Component
class StandardUuidPathConverter : Converter<String, StandardUuidPath> {
    override fun convert(source: String): StandardUuidPath =
        StandardUuidPath(StandardUuid.parse(source))
}

internal object StandardUuid {
    const val ERROR_MESSAGE = "UUID는 하이픈을 포함한 36자 표준 문자열이어야 합니다"

    fun parse(value: String): UUID {
        require(value.length == STANDARD_UUID_LENGTH) { ERROR_MESSAGE }
        val uuid = UUID.fromString(value)
        require(uuid.toString().equals(value, ignoreCase = true)) { ERROR_MESSAGE }
        return uuid
    }

    private const val STANDARD_UUID_LENGTH = 36
}
