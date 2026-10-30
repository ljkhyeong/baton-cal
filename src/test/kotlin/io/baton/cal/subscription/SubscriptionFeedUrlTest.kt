package io.baton.cal.subscription

import io.baton.cal.config.CalProperties
import io.baton.cal.persistence.CalendarSubscriptionRepository
import io.baton.cal.persistence.CalendarSubscriptionRow
import io.baton.cal.persistence.CalendarSubscriptionStatus
import io.baton.cal.projection.SeasonProjectionService
import io.baton.cal.support.anyArg
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.net.URI
import java.time.Clock
import java.util.UUID

class SubscriptionFeedUrlTest {
    @Test
    fun `공개 주소의 스킴 표기와 관계없이 계약 형식의 피드 주소를 만든다`() {
        val repository = mock(CalendarSubscriptionRepository::class.java)
        doReturn(true).`when`(repository).insert(anyArg(CalendarSubscriptionRow::class.java, ROW))
        val service = SubscriptionService(
            repository = repository,
            projectionService = mock(SeasonProjectionService::class.java),
            tokenCodec = SubscriptionTokenCodec(),
            properties = CalProperties(
                internalToken = "secret-internal-token-that-is-long-enough",
                publicBaseUrl = URI.create("HTTPS://Calendar.Example.Test"),
            ),
            clock = Clock.systemUTC(),
            meterRegistry = SimpleMeterRegistry(),
        )

        assertThat(service.create(UUID.randomUUID()).feedUrl.toString())
            .startsWith("https://Calendar.Example.Test/calendars/v1/")
    }

    private companion object {
        val ROW = CalendarSubscriptionRow(
            id = UUID(0, 1),
            seasonId = UUID(0, 2),
            tokenHash = "0".repeat(64),
            credentialGeneration = UUID(0, 3),
            status = CalendarSubscriptionStatus.ACTIVE,
        )
    }
}
