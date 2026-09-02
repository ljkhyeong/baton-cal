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

class RecoveryConflictException private constructor(
    code: String,
    message: String,
) : ApiException(HttpStatus.CONFLICT, code, message) {
    companion object {
        fun manifestMismatch() = RecoveryConflictException(
            code = "RECOVERY_MANIFEST_MISMATCH",
            message = "recovery manifest does not match the current calendar state",
        )

        fun runConflict() = RecoveryConflictException(
            code = "RECOVERY_RUN_CONFLICT",
            message = "recoveryId already represents another manifest",
        )

        fun modeRequired() = RecoveryConflictException(
            code = "RECOVERY_MODE_REQUIRED",
            message = "recovery manifest verification requires recovery mode",
        )
    }
}
