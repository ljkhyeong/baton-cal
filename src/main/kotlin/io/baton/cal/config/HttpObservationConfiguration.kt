package io.baton.cal.config

import io.micrometer.common.KeyValue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention
import org.springframework.http.server.observation.ServerHttpObservationDocumentation
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.http.server.observation.ServerRequestObservationConvention

@Configuration(proxyBeanMethods = false)
class HttpObservationConfiguration {
    @Bean
    fun serverRequestObservationConvention(): ServerRequestObservationConvention =
        PublicCalendarTokenRedactingObservationConvention()
}

private class PublicCalendarTokenRedactingObservationConvention :
    DefaultServerRequestObservationConvention() {
    // 경로 원문은 정규화 전 값이라 접두사로 공개 경로를 가려낼 수 없으므로 기록하지 않는다.
    override fun httpUrl(context: ServerRequestObservationContext): KeyValue =
        ServerHttpObservationDocumentation.HighCardinalityKeyNames.HTTP_URL.withValue(
            if (context.isPublicCalendarRequest()) PUBLIC_CALENDAR_URL_TEMPLATE else context.pathPattern ?: UNKNOWN_URL,
        )

    private fun ServerRequestObservationContext.isPublicCalendarRequest(): Boolean =
        pathPattern?.startsWith(PUBLIC_CALENDAR_PATH_PREFIX) == true ||
            carrier?.requestURI?.startsWith(PUBLIC_CALENDAR_PATH_PREFIX) == true

    private companion object {
        const val PUBLIC_CALENDAR_PATH_PREFIX = "/calendars/v1/"
        const val PUBLIC_CALENDAR_URL_TEMPLATE = "/calendars/v1/{token}.ics"
        const val UNKNOWN_URL = "UNKNOWN"
    }
}
