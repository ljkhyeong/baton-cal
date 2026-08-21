package io.baton.cal.projection

import io.baton.cal.calendar.IcsCalendarRenderer
import io.baton.cal.persistence.CalendarItemRepository
import io.baton.cal.persistence.SeasonFeedProjectionRepository
import io.baton.cal.persistence.SeasonProjectionLockRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.time.Instant
import java.util.UUID

class SeasonProjectionServiceTest {
    private val projectionRepository = mock(SeasonFeedProjectionRepository::class.java)
    private val service = SeasonProjectionService(
        lockRepository = mock(SeasonProjectionLockRepository::class.java),
        itemRepository = mock(CalendarItemRepository::class.java),
        projectionRepository = projectionRepository,
        renderer = mock(IcsCalendarRenderer::class.java),
    )

    @Test
    fun `투영이 없으면 관측 시각의 초를 사용한다`() {
        val observedAt = Instant.parse("2026-08-14T03:04:05.987654Z")

        val acceptedAt = service.nextAcceptedAtWhileLocked(SEASON_ID, observedAt)

        assertThat(acceptedAt).isEqualTo(Instant.parse("2026-08-14T03:04:05Z"))
    }

    @Test
    fun `같은 초이거나 시계가 뒤로 가면 이전 값에서 정확히 1초 전진한다`() {
        val lastModified = Instant.parse("2026-08-14T03:04:05Z")
        doReturn(lastModified).`when`(projectionRepository).findLastModifiedBySeasonId(SEASON_ID)

        val sameSecond = service.nextAcceptedAtWhileLocked(
            SEASON_ID,
            Instant.parse("2026-08-14T03:04:05.999999Z"),
        )
        val clockMovedBackward = service.nextAcceptedAtWhileLocked(
            SEASON_ID,
            Instant.parse("2026-08-14T02:59:59.999999Z"),
        )

        val exactlyOneSecondLater = lastModified.plusSeconds(1)
        assertThat(sameSecond).isEqualTo(exactlyOneSecondLater)
        assertThat(clockMovedBackward).isEqualTo(exactlyOneSecondLater)
    }

    @Test
    fun `시계가 앞서면 관측 시각의 초를 사용한다`() {
        doReturn(Instant.parse("2026-08-14T03:04:05Z"))
            .`when`(projectionRepository).findLastModifiedBySeasonId(SEASON_ID)

        val acceptedAt = service.nextAcceptedAtWhileLocked(
            SEASON_ID,
            Instant.parse("2026-08-14T03:04:10.987654Z"),
        )

        assertThat(acceptedAt).isEqualTo(Instant.parse("2026-08-14T03:04:10Z"))
    }

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    }
}
