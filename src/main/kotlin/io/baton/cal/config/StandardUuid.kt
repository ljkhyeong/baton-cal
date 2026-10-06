package io.baton.cal.config

import org.springframework.web.bind.WebDataBinder
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.InitBinder
import java.beans.PropertyEditorSupport
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * 경로 변수의 UUID를 36자 표준 문자열로만 받는다. 사용자 편집기는 Spring 기본 변환과 그 대체 경로보다
 * 먼저 적용되므로 `1-1-1-1-1` 같은 축약 형식을 다른 경로로 받아들이지 않는다.
 */
@ControllerAdvice
class StandardUuidBinding {
    @InitBinder
    fun registerStandardUuidEditor(binder: WebDataBinder) {
        binder.registerCustomEditor(UUID::class.java, StandardUuidEditor())
    }
}

private class StandardUuidEditor : PropertyEditorSupport() {
    override fun setAsText(text: String) {
        value = StandardUuid.parse(text)
    }
}

internal object StandardUuid {
    const val ERROR_MESSAGE = "UUID는 하이픈을 포함한 36자 표준 문자열이어야 합니다"

    fun parse(value: String): UUID = requireNotNull(parseOrNull(value)) { ERROR_MESSAGE }

    fun parseOrNull(value: String): UUID? = Uuid.parseHexDashOrNull(value)?.toJavaUuid()
}
