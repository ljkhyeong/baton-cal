package io.baton.cal.config

import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

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

    fun parse(value: String): UUID =
        requireNotNull(Uuid.parseHexDashOrNull(value)) { ERROR_MESSAGE }.toJavaUuid()
}
