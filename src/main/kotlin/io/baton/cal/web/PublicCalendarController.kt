package io.baton.cal.web

import io.baton.cal.subscription.SubscriptionService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.WebRequest
import java.nio.charset.StandardCharsets

@RestController
class PublicCalendarController(
    private val subscriptionService: SubscriptionService,
) {
    @GetMapping("/calendars/v1/{token}.ics")
    fun getCalendar(
        @PathVariable token: String,
        request: WebRequest,
        response: HttpServletResponse,
    ) {
        val projection = subscriptionService.findFeed(token)
            ?: run {
                response.status = HttpServletResponse.SC_NOT_FOUND
                return
            }

        response.setHeader(HttpHeaders.ETAG, projection.etag)
        response.setDateHeader(HttpHeaders.LAST_MODIFIED, projection.lastModified.toEpochMilli())
        response.setHeader(HttpHeaders.CACHE_CONTROL, FEED_CACHE_CONTROL.headerValue)
        if (request.checkNotModified(projection.etag, projection.lastModified.toEpochMilli())) {
            // Spring intentionally omits Last-Modified when its value is the
            // Unix epoch, which is CAL's canonical validator for an empty feed.
            response.setDateHeader(HttpHeaders.LAST_MODIFIED, projection.lastModified.toEpochMilli())
            return
        }

        response.contentType = CALENDAR_MEDIA_TYPE.toString()
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, CALENDAR_CONTENT_DISPOSITION.toString())
        response.outputStream.write(projection.representation)
    }

    @GetMapping("/calendars/v1/**")
    fun rejectMalformedCalendarPath(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_NOT_FOUND
    }

    private companion object {
        val FEED_CACHE_CONTROL: CacheControl = CacheControl.noCache().cachePrivate()
        val CALENDAR_MEDIA_TYPE = MediaType("text", "calendar", StandardCharsets.UTF_8)
        val CALENDAR_CONTENT_DISPOSITION: ContentDisposition = ContentDisposition.inline()
            .filename("baton-calendar.ics")
            .build()
    }
}
