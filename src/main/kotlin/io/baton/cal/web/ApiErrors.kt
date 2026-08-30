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

class SnapshotConflictException(
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
