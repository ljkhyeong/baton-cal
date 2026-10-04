package io.baton.cal.projection

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.IcsCalendarRenderer
import io.baton.cal.calendar.ScheduleTimeType
import io.baton.cal.calendar.RenderedCalendar
import io.baton.cal.persistence.CalendarItemRepository
import io.baton.cal.persistence.CalendarItemRow
import io.baton.cal.persistence.SeasonCalendarMetadataRepository
import io.baton.cal.persistence.SeasonCalendarMetadataRow
import io.baton.cal.persistence.SeasonFeedHeaders
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
        doReturn(feedHeaders(lastModified = NOW))
            .`when`(projectionRepository).findHeadersBySeasonId(SEASON_ID)

        service.ensureProjection(SEASON_ID)

        verify(lockRepository, never()).acquire(SEASON_ID)
    }

    @Test
    fun `투영이 없으면 시즌 잠금 뒤 다시 확인한다`() {
        doReturn(null, feedHeaders(lastModified = NOW))
            .`when`(projectionRepository).findHeadersBySeasonId(SEASON_ID)

        service.ensureProjection(SEASON_ID)

        val calls = inOrder(projectionRepository, lockRepository)
        calls.verify(projectionRepository).findHeadersBySeasonId(SEASON_ID)
        calls.verify(lockRepository).acquire(SEASON_ID)
        calls.verify(projectionRepository).findHeadersBySeasonId(SEASON_ID)
        verifyNoInteractions(itemRepository, renderer)
    }

    @Test
    fun `표현이 같으면 기존 Last-Modified를 유지한다`() {
        val existing = feedHeaders(lastModified = Instant.parse("2026-08-14T03:04:05Z"))
        doReturn(existing).`when`(projectionRepository).findHeadersBySeasonId(SEASON_ID)
        stubRendering(etag = existing.etag)

        service.rebuildWhileLocked(SEASON_ID)

        assertThat(savedProjection().lastModified).isEqualTo(existing.lastModified)
        assertThat(meterRegistry.get("baton.cal.projection.rebuild").timer().count()).isEqualTo(1)
        assertThat(meterRegistry.get("baton.cal.projection.items").summary().totalAmount()).isZero()
        assertThat(meterRegistry.get("baton.cal.projection.bytes").summary().totalAmount())
            .isEqualTo("calendar".encodeToByteArray().size.toDouble())
    }

    @Test
    fun `표현이 바뀌면 현재 시각까지 Last-Modified를 전진한다`() {
        doReturn(feedHeaders(lastModified = NOW.minusSeconds(30)))
            .`when`(projectionRepository).findHeadersBySeasonId(SEASON_ID)
        stubRendering(etag = "\"changed\"")

        service.rebuildWhileLocked(SEASON_ID)

        assertThat(savedProjection().lastModified).isEqualTo(NOW)
    }

    @Test
    fun `표현이 바뀌고 시계가 뒤에 있어도 Last-Modified는 현재 시각을 넘지 않는다`() {
        val existingLastModified = NOW.plusSeconds(30)
        doReturn(feedHeaders(lastModified = existingLastModified))
            .`when`(projectionRepository).findHeadersBySeasonId(SEASON_ID)
        stubRendering(etag = "\"changed\"")

        service.rebuildWhileLocked(SEASON_ID)

        assertThat(savedProjection().lastModified).isEqualTo(NOW)
    }

    @Test
    fun `일정 없는 시즌의 첫 이름은 이름 채택 시각을 Last-Modified로 사용한다`() {
        val acceptedAt = NOW.minusSeconds(10)
        doReturn(SeasonCalendarMetadataRow(SEASON_ID, 0, "가을 시즌", acceptedAt))
            .`when`(metadataRepository).findBySeasonId(SEASON_ID)
        doReturn(emptyList<CalendarItemRow>()).`when`(itemRepository).listBySeasonId(SEASON_ID)
        doReturn(RenderedCalendar("calendar".encodeToByteArray(), "\"named\""))
            .`when`(renderer).render(SEASON_ID, emptyList(), "가을 시즌")

        service.rebuildWhileLocked(SEASON_ID)

        assertThat(savedProjection().lastModified).isEqualTo(acceptedAt)
    }

    @Test
    fun `첫 투영은 항목과 이름 중 가장 늦은 채택 시각을 초 단위로 사용한다`() {
        val items = listOf(itemRow("2026-08-20T01:00:00Z"), itemRow("2026-08-20T03:00:00.987Z"))
        doReturn(items).`when`(itemRepository).listBySeasonId(SEASON_ID)
        doReturn(SeasonCalendarMetadataRow(SEASON_ID, 0, "가을 시즌", Instant.parse("2026-08-20T02:00:00Z")))
            .`when`(metadataRepository).findBySeasonId(SEASON_ID)
        doReturn(RenderedCalendar("calendar".encodeToByteArray(), "\"items\""))
            .`when`(renderer).render(SEASON_ID, items.map(CalendarItemRow::toCalendarItem), "가을 시즌")

        service.rebuildWhileLocked(SEASON_ID)

        assertThat(savedProjection().lastModified).isEqualTo(Instant.parse("2026-08-20T03:00:00Z"))
    }

    @Test
    fun `항목과 이름이 없는 첫 투영은 Unix epoch를 Last-Modified로 사용한다`() {
        stubRendering(etag = "\"empty\"")

        service.rebuildWhileLocked(SEASON_ID)

        assertThat(savedProjection().lastModified).isEqualTo(Instant.EPOCH)
    }

    private fun stubRendering(etag: String) {
        doReturn(emptyList<CalendarItemRow>()).`when`(itemRepository).listBySeasonId(SEASON_ID)
        doReturn(RenderedCalendar("calendar".encodeToByteArray(), etag)).`when`(renderer).render(SEASON_ID, emptyList())
    }

    private fun itemRow(acceptedAt: String) = CalendarItemRow(
        sourceItemId = UUID.randomUUID(),
        seasonId = SEASON_ID,
        revision = 0,
        status = CalendarItemStatus.ACTIVE,
        summary = "일정",
        description = null,
        location = null,
        timeType = ScheduleTimeType.UTC_INSTANT,
        startsAtInstant = Instant.parse("2026-09-01T01:00:00Z"),
        endsAtInstant = Instant.parse("2026-09-01T02:00:00Z"),
        startsAtLocal = null,
        endsAtLocal = null,
        zoneId = null,
        startsOnDate = null,
        endsOnDate = null,
        sourceUpdatedAt = Instant.parse("2026-08-20T00:00:00Z"),
        acceptedAt = Instant.parse(acceptedAt),
    )

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

    private fun feedHeaders(lastModified: Instant) =
        SeasonFeedHeaders(etag = "\"same\"", lastModified = lastModified, contentLength = 8)

    private companion object {
        val SEASON_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val NOW: Instant = Instant.parse("2026-08-14T03:04:10Z")
    }
}
