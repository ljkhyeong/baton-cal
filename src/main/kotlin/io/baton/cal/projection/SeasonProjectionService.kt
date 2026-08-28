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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

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
) {
    @Transactional
    fun rebuild(seasonId: UUID): ProjectionResult {
        lockRepository.acquire(seasonId)
        return rebuildWhileLocked(seasonId)
    }

    @Transactional
    fun ensureProjection(seasonId: UUID) {
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
    ): ProjectionResult {
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
        return ProjectionResult(
            seasonId = seasonId,
            etag = rendered.etag,
            itemCount = items.size,
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun nextAcceptedAtWhileLocked(
        seasonId: UUID,
        observedAt: Instant,
    ): Instant {
        val observedSecond = observedAt.truncatedTo(ChronoUnit.SECONDS)
        return projectionRepository.findMetadataBySeasonId(seasonId)
            ?.lastModified
            ?.plusSeconds(1)
            ?.coerceAtLeast(observedSecond)
            ?: observedSecond
    }

    private fun resolveLastModified(
        existing: SeasonFeedProjectionMetadata?,
        etag: String,
        renderedLastModified: Instant,
    ): Instant = when {
        existing == null -> renderedLastModified
        existing.etag == etag -> existing.lastModified
        else -> maxOf(
            renderedLastModified,
            existing.lastModified.plusSeconds(1),
            clock.instant().truncatedTo(ChronoUnit.SECONDS),
        )
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
