package io.baton.cal.projection

import io.baton.cal.calendar.IcsCalendarRenderer
import io.baton.cal.calendar.RenderedCalendar
import io.baton.cal.persistence.CalendarItemRepository
import io.baton.cal.persistence.CalendarItemRow
import io.baton.cal.persistence.SeasonCalendarMetadataRepository
import io.baton.cal.persistence.SeasonCalendarMetadataRow
import io.baton.cal.persistence.SeasonFeedProjectionMetadata
import io.baton.cal.persistence.SeasonFeedProjectionRepository
import io.baton.cal.persistence.SeasonFeedProjectionRow
import io.baton.cal.persistence.SeasonProjectionLockRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SeasonProjectionServiceTest {
    private val itemRepository = mock(CalendarItemRepository::class.java)
    private val projectionRepository = mock(SeasonFeedProjectionRepository::class.java)
    private val metadataRepository = mock(SeasonCalendarMetadataRepository::class.java)
    private val lockRepository = mock(SeasonProjectionLockRepository::class.java)
    private val renderer = mock(IcsCalendarRenderer::class.java)
    private val meterRegistry = SimpleMeterRegistry()
    private val service = SeasonProjectionService(
        lockRepository = lockRepository,
        itemRepository = itemRepository,
        projectionRepository = projectionRepository,
        metadataRepository = metadataRepository,
        renderer = renderer,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        meterRegistry = meterRegistry,
    )

    @Test
    fun `기존 투영이 있으면 시즌 잠금을 잡지 않는다`() {
        doReturn(metadata(lastModified = NOW))
            .`when`(projectionRepository).findMetadataBySeasonId(SEASON_ID)

        service.ensureProjection(SEASON_ID)

        verify(lockRepository, never()).acquire(SEASON_ID)
    }

    @Test
    fun `투영이 없으면 시즌 잠금 뒤 다시 확인한다`() {
        doReturn(null, metadata(lastModified = NOW))
            .`when`(projectionRepository).findMetadataBySeasonId(SEASON_ID)

        service.ensureProjection(SEASON_ID)

        val calls = inOrder(projectionRepository, lockRepository)
        calls.verify(projectionRepository).findMetadataBySeasonId(SEASON_ID)
        calls.verify(lockRepository).acquire(SEASON_ID)
        calls.verify(projectionRepository).findMetadataBySeasonId(SEASON_ID)
        verifyNoInteractions(itemRepository, renderer)
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
    fun `표현이 바뀌고 시계가 뒤에 있어도 Last-Modified는 현재 시각을 넘지 않는다`() {
        val existingLastModified = NOW.plusSeconds(30)
        doReturn(metadata(lastModified = existingLastModified))
            .`when`(projectionRepository).findMetadataBySeasonId(SEASON_ID)
        stubRendering(etag = "\"changed\"", lastModified = NOW.minusSeconds(20))

        service.rebuildWhileLocked(SEASON_ID)

        assertThat(savedProjection().lastModified).isEqualTo(NOW)
    }

    @Test
    fun `일정 없는 시즌의 첫 이름은 이름 채택 시각을 Last-Modified로 사용한다`() {
        val acceptedAt = NOW.minusSeconds(10)
        doReturn(SeasonCalendarMetadataRow(SEASON_ID, 0, "가을 시즌", acceptedAt))
            .`when`(metadataRepository).findBySeasonId(SEASON_ID)
        doReturn(emptyList<CalendarItemRow>()).`when`(itemRepository).listBySeasonId(SEASON_ID)
        doReturn(RenderedCalendar("calendar".encodeToByteArray(), "\"named\"", Instant.EPOCH))
            .`when`(renderer).render(SEASON_ID, emptyList(), "가을 시즌")

        service.rebuildWhileLocked(SEASON_ID)

        assertThat(savedProjection().lastModified).isEqualTo(acceptedAt)
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
