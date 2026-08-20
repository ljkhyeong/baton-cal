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
    override fun httpUrl(context: ServerRequestObservationContext): KeyValue =
        if (context.isPublicCalendarRequest()) {
            ServerHttpObservationDocumentation.HighCardinalityKeyNames.HTTP_URL
                .withValue(PUBLIC_CALENDAR_URL_TEMPLATE)
        } else {
            super.httpUrl(context)
        }

    private fun ServerRequestObservationContext.isPublicCalendarRequest(): Boolean =
        pathPattern?.startsWith(PUBLIC_CALENDAR_PATH_PREFIX) == true ||
            carrier?.requestURI?.startsWith(PUBLIC_CALENDAR_PATH_PREFIX) == true

    private companion object {
        const val PUBLIC_CALENDAR_PATH_PREFIX = "/calendars/v1/"
        const val PUBLIC_CALENDAR_URL_TEMPLATE = "/calendars/v1/{token}.ics"
    }
}
