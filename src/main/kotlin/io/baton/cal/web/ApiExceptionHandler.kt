package io.baton.cal.web

import org.slf4j.LoggerFactory
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.transaction.TransactionTimedOutException
import tools.jackson.core.exc.StreamConstraintsException

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ResponseEntity<ApiErrorResponse> = ResponseEntity
        .status(exception.status)
        .body(ApiErrorResponse(exception.code, exception.message))

    @ExceptionHandler(
        CannotAcquireLockException::class,
        QueryTimeoutException::class,
        TransactionTimedOutException::class,
    )
    fun handleTemporaryDatabaseContention(): ResponseEntity<ApiErrorResponse> = ResponseEntity
        .status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        .body(ApiErrorResponse("SERVICE_BUSY", "service is temporarily busy"))

    override fun handleHttpMessageNotReadable(
        exception: HttpMessageNotReadableException,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        if (exception.contains(StreamConstraintsException::class.java)) {
            return ResponseEntity(
                ApiErrorResponse(
                    code = "REQUEST_TOO_LARGE",
                    message = "request body exceeds the maximum size",
                ),
                headers,
                HttpStatus.CONTENT_TOO_LARGE,
            )
        }
        return super.handleHttpMessageNotReadable(exception, headers, statusCode, request)
    }

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

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(
        exception: Exception,
    ): ApiErrorResponse {
        // 예외 메시지에는 자격 증명이 포함된 공개 캘린더 경로가 들어갈 수 있으므로
        // 일반 오류 로그에는 예외 형식만 남긴다.
        applicationLogger.error("Unhandled API request failure of type {}", exception.javaClass.name)
        return ApiErrorResponse("INTERNAL_ERROR", "an unexpected error occurred")
    }

    companion object {
        const val RETRY_AFTER_SECONDS = "1"
        private val applicationLogger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
