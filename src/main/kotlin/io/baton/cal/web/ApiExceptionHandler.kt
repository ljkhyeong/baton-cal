package io.baton.cal.web

import org.slf4j.LoggerFactory
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.CannotCreateTransactionException
import tools.jackson.core.exc.StreamConstraintsException

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ResponseEntity<Any> = ResponseEntity
        .status(exception.status)
        .errorBody(ApiErrorResponse(exception.code, exception.message))

    @ExceptionHandler(
        TransientDataAccessException::class,
        TransactionTimedOutException::class,
        CannotGetJdbcConnectionException::class,
        CannotCreateTransactionException::class,
    )
    fun handleTemporaryDatabaseFailure(): ResponseEntity<Any> = ResponseEntity
        .status(HttpStatus.SERVICE_UNAVAILABLE)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        .errorBody(ApiErrorResponse("SERVICE_BUSY", "service is temporarily busy"))

    override fun handleHttpMessageNotReadable(
        exception: HttpMessageNotReadableException,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        if (exception.contains(StreamConstraintsException::class.java)) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).headers(headers).errorBody(
                ApiErrorResponse(
                    code = "REQUEST_TOO_LARGE",
                    message = "request body exceeds the maximum size",
                ),
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
        val error = when {
            statusCode == HttpStatus.NOT_FOUND -> ApiErrorResponse("RESOURCE_NOT_FOUND", "resource was not found")
            statusCode.is4xxClientError -> ApiErrorResponse("INVALID_REQUEST", "request is invalid")
            else -> {
                applicationLogger.error("Unhandled MVC request failure of type {}", exception.javaClass.name)
                ApiErrorResponse("INTERNAL_ERROR", "an unexpected error occurred")
            }
        }
        return ResponseEntity.status(statusCode).headers(headers).errorBody(error)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(
        exception: Exception,
    ): ResponseEntity<Any> {
        // 예외 메시지에는 자격 증명이 포함된 공개 캘린더 경로가 들어갈 수 있으므로
        // 일반 오류 로그에는 예외 형식만 남긴다.
        applicationLogger.error("Unhandled API request failure of type {}", exception.javaClass.name)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .errorBody(ApiErrorResponse("INTERNAL_ERROR", "an unexpected error occurred"))
    }

    /** 캘린더 앱의 `Accept: text/calendar`에도 오류 계약을 지키도록 본문 형식을 협상하지 않고 지정한다. */
    private fun ResponseEntity.BodyBuilder.errorBody(error: ApiErrorResponse): ResponseEntity<Any> =
        contentType(MediaType.APPLICATION_JSON).body(error)

    companion object {
        const val RETRY_AFTER_SECONDS = "1"
        private val applicationLogger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
