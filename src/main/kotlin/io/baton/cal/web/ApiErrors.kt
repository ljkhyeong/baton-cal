package io.baton.cal.web

import org.springframework.http.HttpStatus

data class ApiErrorResponse(
    val code: String,
    val message: String,
)

open class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
) : RuntimeException(message)

class InvalidApiRequestException(message: String) : ApiException(
    status = HttpStatus.BAD_REQUEST,
    code = "INVALID_REQUEST",
    message = message,
)

/** 이미 저장된 상태와 맞지 않는 요청이다. 구체적인 원인은 `code`로 구분한다. */
class ConflictException(
    code: String,
    message: String,
) : ApiException(
    status = HttpStatus.CONFLICT,
    code = code,
    message = message,
)

class InternalResourceNotFoundException(message: String) : ApiException(
    status = HttpStatus.NOT_FOUND,
    code = "RESOURCE_NOT_FOUND",
    message = message,
)

class RecoveryInProgressException : ApiException(
    status = HttpStatus.SERVICE_UNAVAILABLE,
    code = "RECOVERY_IN_PROGRESS",
    message = "subscription issuance is disabled during recovery",
)
