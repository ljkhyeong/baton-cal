package io.baton.cal.projection

import io.baton.cal.calendar.CalendarItem
import io.baton.cal.calendar.IcsCalendarRenderer
import io.baton.cal.calendar.ScheduleTimeType
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.persistence.CalendarItemRepository
import io.baton.cal.persistence.CalendarItemRow
import io.baton.cal.persistence.SeasonFeedProjectionMetadata
import io.baton.cal.persistence.SeasonFeedProjectionRepository
import io.baton.cal.persistence.SeasonFeedProjectionRow
import io.baton.cal.persistence.SeasonProjectionLockRepository
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.function.Supplier

data class ProjectionResult(
    val seasonId: UUID,
    val etag: String,
    val itemCount: Int,
)

@Service
class SeasonProjectionService(
    private val lockRepository: SeasonProjectionLockRepository,
    private val itemRepository: CalendarItemRepository,
    private val projectionRepository: SeasonFeedProjectionRepository,
    private val renderer: IcsCalendarRenderer,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    private val rebuildTimer: Timer = Timer.builder("baton.cal.projection.rebuild")
        .description("시즌 피드 전체 재구축 시간")
        .register(meterRegistry)
    private val itemCountSummary: DistributionSummary = DistributionSummary
        .builder("baton.cal.projection.items")
        .description("재구축한 시즌의 일정 항목 수")
        .baseUnit("items")
        .register(meterRegistry)
    private val byteSizeSummary: DistributionSummary = DistributionSummary
        .builder("baton.cal.projection.bytes")
        .description("재구축한 iCalendar 표현 크기")
        .baseUnit("bytes")
        .register(meterRegistry)

    @Transactional
    fun rebuild(seasonId: UUID): ProjectionResult {
        lockRepository.acquire(seasonId)
        return rebuildWhileLocked(seasonId)
    }

    @Transactional
    fun ensureProjection(seasonId: UUID) {
        if (projectionRepository.findMetadataBySeasonId(seasonId) != null) {
            return
        }

        lockRepository.acquire(seasonId)
        if (projectionRepository.findMetadataBySeasonId(seasonId) == null) {
            rebuildWhileLocked(seasonId, existing = null)
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun rebuildWhileLocked(seasonId: UUID): ProjectionResult =
        rebuildWhileLocked(seasonId, projectionRepository.findMetadataBySeasonId(seasonId))

    private fun rebuildWhileLocked(
        seasonId: UUID,
        existing: SeasonFeedProjectionMetadata?,
    ): ProjectionResult =
        rebuildTimer.record(Supplier {
            val items = itemRepository.listBySeasonId(seasonId).map(CalendarItemRow::toDomain)
            val rendered = renderer.render(seasonId = seasonId, items = items)
            projectionRepository.upsert(
                SeasonFeedProjectionRow(
                    seasonId = seasonId,
                    representation = rendered.bytes,
                    etag = rendered.etag,
                    lastModified = resolveLastModified(existing, rendered.etag, rendered.lastModified),
                ),
            )
            itemCountSummary.record(items.size.toDouble())
            byteSizeSummary.record(rendered.bytes.size.toDouble())
            ProjectionResult(
                seasonId = seasonId,
                etag = rendered.etag,
                itemCount = items.size,
            )
        })

    private fun resolveLastModified(
        existing: SeasonFeedProjectionMetadata?,
        etag: String,
        renderedLastModified: Instant,
    ): Instant {
        val currentSecond = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        return when {
            existing == null -> renderedLastModified.coerceAtMost(currentSecond)
            existing.etag == etag -> existing.lastModified.coerceAtMost(currentSecond)
            else -> currentSecond
        }
    }
}

private fun CalendarItemRow.toDomain(): CalendarItem = CalendarItem(
    sourceItemId = sourceItemId,
    revision = revision,
    status = status,
    summary = summary,
    description = description,
    location = location,
    schedule = when (timeType) {
        ScheduleTimeType.UTC_INSTANT -> ScheduleWindow.UtcInstant(
            start = requireNotNull(startsAtInstant),
            end = requireNotNull(endsAtInstant),
        )

        ScheduleTimeType.UTC_POINT -> ScheduleWindow.UtcPoint(
            at = requireNotNull(startsAtInstant),
        )

        ScheduleTimeType.ZONED_LOCAL -> ScheduleWindow.ZonedLocal(
            start = requireNotNull(startsAtLocal),
            end = requireNotNull(endsAtLocal),
            zoneId = requireNotNull(zoneId),
        )

        ScheduleTimeType.ZONED_LOCAL_POINT -> ScheduleWindow.ZonedLocalPoint(
            at = requireNotNull(startsAtLocal),
            zoneId = requireNotNull(zoneId),
        )

        ScheduleTimeType.ALL_DAY -> ScheduleWindow.AllDay(
            startDate = requireNotNull(startsOnDate),
            endDate = requireNotNull(endsOnDate),
        )
    },
    sourceUpdatedAt = sourceUpdatedAt,
    acceptedAt = acceptedAt,
)
