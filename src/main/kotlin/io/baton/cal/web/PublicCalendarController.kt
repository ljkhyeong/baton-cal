package io.baton.cal.web

import io.baton.cal.subscription.SubscriptionService
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
    ): ResponseEntity<ByteArray> {
        val projection = subscriptionService.findFeed(token)
            ?: return ResponseEntity.notFound().build()

        if (request.checkNotModified(projection.etag, projection.lastModified.toEpochMilli())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .cacheControl(FEED_CACHE_CONTROL)
                .build()
        }

        return ResponseEntity.ok()
            .eTag(projection.etag)
            .lastModified(projection.lastModified)
            .cacheControl(FEED_CACHE_CONTROL)
            .contentType(CALENDAR_MEDIA_TYPE)
            .headers { it.contentDisposition = CALENDAR_CONTENT_DISPOSITION }
            .body(projection.representation)
    }

    private companion object {
        val FEED_CACHE_CONTROL: CacheControl = CacheControl.noCache().cachePrivate()
        val CALENDAR_MEDIA_TYPE = MediaType("text", "calendar", StandardCharsets.UTF_8)
        val CALENDAR_CONTENT_DISPOSITION: ContentDisposition = ContentDisposition.inline()
            .filename("baton-calendar.ics")
            .build()
    }
}
