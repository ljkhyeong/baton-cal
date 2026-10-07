package io.baton.cal.web

import org.springframework.http.HttpStatus

data class ApiErrorResponse(
    val code: String,
    val message: String,
)

/** 계약의 오류 응답으로 바로 바뀌는 요청 실패다. 처리기는 `status`·`code`·`message`만 응답한다. */
class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
) : RuntimeException(message)

fun invalidRequest(message: String) = ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message)

/** 이미 저장된 상태와 맞지 않는 요청이다. 구체적인 원인은 `code`로 구분한다. */
fun conflict(code: String, message: String) = ApiException(HttpStatus.CONFLICT, code, message)

fun resourceNotFound(message: String) = ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message)
