package io.baton.cal.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.baton.cal.contract.ContractSchemaSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class ApiExceptionHandlerTest {
    private val mockMvc = MockMvcBuilders.standaloneSetup(FailureController())
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    @ParameterizedTest(name = "{0} 데이터베이스 실패")
    @ValueSource(strings = ["lock", "query", "transaction", "connection", "transaction-start"])
    fun `데이터베이스 연결 실패와 제한 시간 초과는 재시도 가능한 503을 반환한다`(failureType: String) {
        val result = mockMvc
            .perform(get("/internal/api/v1/temporary-database-contention/{failureType}", failureType))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, ApiExceptionHandler.RETRY_AFTER_SECONDS))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
            .andExpect(jsonPath("$.message").value("service is temporarily busy"))
            .andReturn()

        ContractSchemaSupport.assertValid(
            "api-error.v1.schema.json",
            result.response.contentAsString,
            "데이터베이스 실패 응답",
        )
    }

    @Test
    fun `예상 밖 오류는 고정 응답을 반환하고 예외 메시지를 노출하지 않는다`() {
        val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            val result = mockMvc.perform(get("/internal/api/v1/failure"))
                .andExpect(status().isInternalServerError)
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("an unexpected error occurred"))
                .andReturn()

            ContractSchemaSupport.assertValid(
                "api-error.v1.schema.json",
                result.response.contentAsString,
                "예상 밖 오류의 실제 응답",
            )
            assertThat(result.response.contentAsString).doesNotContain(SENSITIVE_VALUE)
            assertThat(appender.list).isNotEmpty
            assertThat(appender.list).allSatisfy { event ->
                assertThat(event.formattedMessage).doesNotContain(SENSITIVE_VALUE)
                assertThat(event.throwableProxy).isNull()
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @RestController
    private class FailureController {
        @GetMapping("/internal/api/v1/failure")
        fun fail(): Nothing = throw IllegalStateException(SENSITIVE_VALUE)

        @GetMapping("/internal/api/v1/temporary-database-contention/lock")
        fun lockTimeout(): Nothing = throw CannotAcquireLockException(SENSITIVE_VALUE)

        @GetMapping("/internal/api/v1/temporary-database-contention/query")
        fun queryTimeout(): Nothing = throw QueryTimeoutException(SENSITIVE_VALUE)

        @GetMapping("/internal/api/v1/temporary-database-contention/transaction")
        fun transactionTimeout(): Nothing = throw TransactionTimedOutException(SENSITIVE_VALUE)

        @GetMapping("/internal/api/v1/temporary-database-contention/connection")
        fun connectionFailure(): Nothing = throw CannotGetJdbcConnectionException(SENSITIVE_VALUE)

        @GetMapping("/internal/api/v1/temporary-database-contention/transaction-start")
        fun transactionStartFailure(): Nothing = throw CannotCreateTransactionException(SENSITIVE_VALUE)
    }

    private companion object {
        const val SENSITIVE_VALUE =
            "https://calendar.example.test/calendars/v1/sensitive-token.ics"
    }
}
