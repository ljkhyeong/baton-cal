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
        return rebuildWhileLocked(seasonId).toResult()
    }

    @Transactional
    fun ensureProjection(seasonId: UUID): ProjectionResult {
        lockRepository.acquire(seasonId)
        return (projectionRepository.findBySeasonId(seasonId) ?: rebuildWhileLocked(seasonId)).toResult()
    }

    fun rebuildWhileLocked(seasonId: UUID): SeasonFeedProjectionRow {
        val rendered = renderer.render(
            seasonId = seasonId,
            items = itemRepository.listBySeasonId(seasonId).map(CalendarItemRow::toDomain),
        )
        return projectionRepository.upsert(
            SeasonFeedProjectionRow(
                seasonId = seasonId,
                representation = rendered.bytes,
                etag = rendered.etag,
                lastModified = rendered.lastModified,
                itemCount = rendered.itemCount,
                rebuiltAt = clock.instant(),
            ),
        )
    }

    fun nextAcceptedAtWhileLocked(
        seasonId: UUID,
        observedAt: Instant,
    ): Instant {
        val current = projectionRepository.findBySeasonId(seasonId)
        val observedSecond = observedAt.truncatedTo(ChronoUnit.SECONDS)
        return if (current == null || observedSecond.isAfter(current.lastModified)) {
            observedSecond
        } else {
            current.lastModified.plusSeconds(1)
        }
    }

    fun findProjection(seasonId: UUID): SeasonFeedProjectionRow? =
        projectionRepository.findBySeasonId(seasonId)

    private fun SeasonFeedProjectionRow.toResult(): ProjectionResult = ProjectionResult(
        seasonId = seasonId,
        etag = etag,
        itemCount = itemCount,
    )

}

private fun CalendarItemRow.toDomain(): CalendarItem = CalendarItem(
    sourceItemId = sourceItemId,
    seasonId = seasonId,
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

        ScheduleTimeType.ZONED_LOCAL -> ScheduleWindow.ZonedLocal(
            start = requireNotNull(startsAtLocal),
            end = requireNotNull(endsAtLocal),
            zoneId = requireNotNull(zoneId),
        )
    },
    sourceUpdatedAt = sourceUpdatedAt,
    acceptedAt = acceptedAt,
)
