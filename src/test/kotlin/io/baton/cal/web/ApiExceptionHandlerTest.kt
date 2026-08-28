package io.baton.cal.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.baton.cal.contract.ContractSchemaSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class ApiExceptionHandlerTest {
    @Test
    fun `예상 밖 오류는 고정 응답을 반환하고 예외 메시지를 노출하지 않는다`() {
        val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            val result = MockMvcBuilders.standaloneSetup(FailureController())
                .setControllerAdvice(ApiExceptionHandler())
                .build()
                .perform(get("/internal/api/v1/failure"))
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
    }

    private companion object {
        const val SENSITIVE_VALUE =
            "https://calendar.example.test/calendars/v1/sensitive-token.ics"
    }
}
