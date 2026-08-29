package io.baton.cal.web

import io.baton.cal.subscription.SubscriptionService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.WebRequest
import java.time.Instant

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
        if (request.hasCacheValidator()) {
            val metadata = subscriptionService.findFeedMetadata(token)
                ?: run {
                    response.status = HttpServletResponse.SC_NOT_FOUND
                    return
                }
            if (request.respondNotModified(response, metadata.etag, metadata.lastModified)) return

            // 본문 조회 사이에 투영이나 구독 상태가 바뀔 수 있으므로 전체 조회에서 다시 판정한다.
            response.reset()
        }

        val projection = subscriptionService.findFeed(token)
            ?: run {
                response.status = HttpServletResponse.SC_NOT_FOUND
                return
        }
        if (request.respondNotModified(response, projection.etag, projection.lastModified)) return

        response.contentType = CALENDAR_MEDIA_TYPE.toString()
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, CALENDAR_CONTENT_DISPOSITION.toString())
        response.outputStream.write(projection.representation)
    }

    @GetMapping("/calendars/v1/**")
    fun rejectMalformedCalendarPath(): ResponseEntity<Void> = ResponseEntity.notFound().build()

    private fun WebRequest.hasCacheValidator(): Boolean =
        getHeader(HttpHeaders.IF_NONE_MATCH) != null || getHeader(HttpHeaders.IF_MODIFIED_SINCE) != null

    private fun WebRequest.respondNotModified(
        response: HttpServletResponse,
        etag: String,
        lastModified: Instant,
    ): Boolean {
        val lastModifiedMillis = lastModified.toEpochMilli()
        response.setHeader(HttpHeaders.CACHE_CONTROL, FEED_CACHE_CONTROL.headerValue)
        val notModified = checkNotModified(etag, lastModifiedMillis)
        if (lastModifiedMillis == 0L) {
            // Spring은 빈 피드의 기준 검증 값인 Unix epoch만 Last-Modified에서 생략한다.
            response.setDateHeader(HttpHeaders.LAST_MODIFIED, lastModifiedMillis)
        }
        return notModified
    }

    private companion object {
        val FEED_CACHE_CONTROL: CacheControl = CacheControl.noCache().cachePrivate()
        val CALENDAR_MEDIA_TYPE = MediaType("text", "calendar", Charsets.UTF_8)
        val CALENDAR_CONTENT_DISPOSITION: ContentDisposition = ContentDisposition.inline()
            .filename("baton-calendar.ics")
            .build()
    }
}
