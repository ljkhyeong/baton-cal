package io.baton.cal.projection

import io.baton.cal.calendar.IcsCalendarRenderer
import io.baton.cal.calendar.RenderedCalendar
import io.baton.cal.persistence.CalendarItemRepository
import io.baton.cal.persistence.CalendarItemRow
import io.baton.cal.persistence.SeasonFeedProjectionMetadata
import io.baton.cal.persistence.SeasonFeedProjectionRepository
import io.baton.cal.persistence.SeasonFeedProjectionRow
import io.baton.cal.persistence.SeasonProjectionLockRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SeasonProjectionServiceTest {
    private val itemRepository = mock(CalendarItemRepository::class.java)
    private val projectionRepository = mock(SeasonFeedProjectionRepository::class.java)
    private val renderer = mock(IcsCalendarRenderer::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val service = SeasonProjectionService(
        lockRepository = mock(SeasonProjectionLockRepository::class.java),
        itemRepository = itemRepository,
        projectionRepository = projectionRepository,
        renderer = renderer,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        meterRegistry = meterRegistry,
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
        doReturn(metadata(lastModified = lastModified))
            .`when`(projectionRepository).findMetadataBySeasonId(SEASON_ID)

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
        doReturn(metadata(lastModified = Instant.parse("2026-08-14T03:04:05Z")))
            .`when`(projectionRepository).findMetadataBySeasonId(SEASON_ID)

        val acceptedAt = service.nextAcceptedAtWhileLocked(
            SEASON_ID,
            Instant.parse("2026-08-14T03:04:10.987654Z"),
        )

        assertThat(acceptedAt).isEqualTo(Instant.parse("2026-08-14T03:04:10Z"))
    }

    @Test
    fun `표현이 같으면 기존 Last-Modified를 유지한다`() {
        val existing = metadata(lastModified = Instant.parse("2026-08-14T03:04:05Z"))
        doReturn(existing).`when`(projectionRepository).findMetadataBySeasonId(SEASON_ID)
        stubRendering(etag = existing.etag, lastModified = NOW.plusSeconds(30))

        service.rebuildWhileLocked(SEASON_ID)

        assertThat(savedProjection().lastModified).isEqualTo(existing.lastModified)
        assertThat(meterRegistry.get("baton.cal.projection.rebuild").timer().count()).isEqualTo(1)
        assertThat(meterRegistry.get("baton.cal.projection.items").summary().totalAmount()).isZero()
        assertThat(meterRegistry.get("baton.cal.projection.bytes").summary().totalAmount())
            .isEqualTo("calendar".encodeToByteArray().size.toDouble())
    }

    @Test
    fun `표현이 바뀌면 현재 시각까지 Last-Modified를 전진한다`() {
        doReturn(metadata(lastModified = NOW.minusSeconds(30)))
            .`when`(projectionRepository).findMetadataBySeasonId(SEASON_ID)
        stubRendering(etag = "\"changed\"", lastModified = NOW.minusSeconds(20))

        service.rebuildWhileLocked(SEASON_ID)

        assertThat(savedProjection().lastModified).isEqualTo(NOW)
    }

    @Test
    fun `표현이 바뀌고 시계가 뒤에 있으면 기존 Last-Modified에서 1초 전진한다`() {
        val existingLastModified = NOW.plusSeconds(30)
        doReturn(metadata(lastModified = existingLastModified))
            .`when`(projectionRepository).findMetadataBySeasonId(SEASON_ID)
        stubRendering(etag = "\"changed\"", lastModified = NOW.minusSeconds(20))

        service.rebuildWhileLocked(SEASON_ID)

        assertThat(savedProjection().lastModified).isEqualTo(existingLastModified.plusSeconds(1))
    }

    private fun stubRendering(
        etag: String,
        lastModified: Instant,
    ) {
        doReturn(emptyList<CalendarItemRow>()).`when`(itemRepository).listBySeasonId(SEASON_ID)
        doReturn(
            RenderedCalendar(
                bytes = "calendar".encodeToByteArray(),
                etag = etag,
                lastModified = lastModified,
            ),
        ).`when`(renderer).render(SEASON_ID, emptyList())
    }

    private fun savedProjection(): SeasonFeedProjectionRow {
        val captor = ArgumentCaptor.forClass(SeasonFeedProjectionRow::class.java)
        verify(projectionRepository).upsert(
            captor.capture() ?: SeasonFeedProjectionRow(
                seasonId = SEASON_ID,
                representation = byteArrayOf(),
                etag = "",
                lastModified = Instant.EPOCH,
            ),
        )
        return captor.value
    }

    private fun metadata(
        etag: String = "\"same\"",
        lastModified: Instant,
    ) = SeasonFeedProjectionMetadata(etag = etag, lastModified = lastModified)

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val NOW: Instant = Instant.parse("2026-08-14T03:04:10Z")
    }
}
