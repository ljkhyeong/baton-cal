package io.baton.cal.web

import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.slf4j.LoggerFactory

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ResponseEntity<ApiErrorResponse> = ResponseEntity
        .status(exception.status)
        .body(ApiErrorResponse(exception.code, exception.message))

    override fun handleExceptionInternal(
        exception: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val error = if (statusCode.is4xxClientError) {
            ApiErrorResponse("INVALID_REQUEST", "request is invalid")
        } else {
            applicationLogger.error("Unhandled MVC request failure of type {}", exception.javaClass.name)
            ApiErrorResponse("INTERNAL_ERROR", "an unexpected error occurred")
        }
        return ResponseEntity(error, headers, statusCode)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(
        exception: Exception,
    ): ResponseEntity<ApiErrorResponse> {
        // Exception messages may contain a request path. Public calendar paths
        // contain credentials, so the generic log deliberately records only type.
        applicationLogger.error("Unhandled API request failure of type {}", exception.javaClass.name)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorResponse("INTERNAL_ERROR", "an unexpected error occurred"))
    }

    companion object {
        private val applicationLogger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
