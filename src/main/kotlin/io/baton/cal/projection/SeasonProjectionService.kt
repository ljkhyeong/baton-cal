package io.baton.cal.projection

import io.baton.cal.calendar.CalendarItem
import io.baton.cal.calendar.IcsCalendarRenderer
import io.baton.cal.calendar.ScheduleTimeType
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.persistence.CalendarItemRepository
import io.baton.cal.persistence.CalendarItemRow
import io.baton.cal.persistence.SeasonFeedProjectionRepository
import io.baton.cal.persistence.SeasonFeedProjectionRow
import io.baton.cal.persistence.SeasonProjectionLockRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
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
) {
    @Transactional
    fun rebuild(seasonId: UUID): ProjectionResult {
        lockRepository.acquire(seasonId)
        return rebuildWhileLocked(seasonId)
    }

    @Transactional
    fun ensureProjection(seasonId: UUID) {
        lockRepository.acquire(seasonId)
        if (projectionRepository.findLastModifiedBySeasonId(seasonId) == null) rebuildWhileLocked(seasonId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun rebuildWhileLocked(seasonId: UUID): ProjectionResult {
        val items = itemRepository.listBySeasonId(seasonId).map(CalendarItemRow::toDomain)
        val rendered = renderer.render(seasonId = seasonId, items = items)
        projectionRepository.upsert(
            SeasonFeedProjectionRow(
                seasonId = seasonId,
                representation = rendered.bytes,
                etag = rendered.etag,
                lastModified = rendered.lastModified,
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
        return projectionRepository.findLastModifiedBySeasonId(seasonId)
            ?.plusSeconds(1)
            ?.coerceAtLeast(observedSecond)
            ?: observedSecond
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
