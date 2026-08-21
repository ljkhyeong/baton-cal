package io.baton.cal.snapshot

import io.baton.cal.calendar.ScheduleTimeType
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.persistence.CalendarItemApplyOutcome
import io.baton.cal.persistence.CalendarItemRepository
import io.baton.cal.persistence.CalendarItemRow
import io.baton.cal.persistence.SeasonProjectionLockRepository
import io.baton.cal.persistence.SourceEventInboxRepository
import io.baton.cal.persistence.SourceEventInboxRow
import io.baton.cal.projection.SeasonProjectionService
import io.baton.cal.web.SnapshotConflictException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class SnapshotIngestionService(
    private val inboxRepository: SourceEventInboxRepository,
    private val itemRepository: CalendarItemRepository,
    private val lockRepository: SeasonProjectionLockRepository,
    private val projectionService: SeasonProjectionService,
    private val clock: Clock,
) {
    @Transactional
    fun ingest(snapshot: ScheduleSnapshot): SnapshotIngestionResult {
        val payloadHash = SnapshotFingerprint.sha256(snapshot)
        val receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        lockRepository.acquire(snapshot.seasonId)

        val inserted = inboxRepository.insert(
            SourceEventInboxRow(
                eventId = snapshot.eventId,
                payloadHash = payloadHash,
                sourceItemId = snapshot.sourceItemId,
                seasonId = snapshot.seasonId,
                sourceRevision = snapshot.revision,
                occurredAt = snapshot.occurredAt,
                receivedAt = receivedAt,
            ),
        )

        if (!inserted) {
            return classifyEventReplay(
                inboxRepository.getPayloadHashByEventId(snapshot.eventId),
                payloadHash,
            )
        }

        val existingRevisionHash = inboxRepository.findPayloadHashBySourceItemIdAndRevision(
            snapshot.sourceItemId,
            snapshot.revision,
            snapshot.eventId,
        )
        if (existingRevisionHash != null) {
            if (existingRevisionHash == payloadHash) return SnapshotIngestionResult.DUPLICATE
            throw SnapshotConflictException(
                code = "SOURCE_REVISION_CONFLICT",
                message = "source revision already represents different content",
            )
        }

        val acceptedAt = projectionService.nextAcceptedAtWhileLocked(snapshot.seasonId, receivedAt)
        return when (itemRepository.applyIfNewer(snapshot.toRow(acceptedAt))) {
            CalendarItemApplyOutcome.APPLIED -> {
                projectionService.rebuildWhileLocked(snapshot.seasonId)
                SnapshotIngestionResult.APPLIED
            }

            CalendarItemApplyOutcome.STALE -> SnapshotIngestionResult.STALE
            CalendarItemApplyOutcome.SCOPE_CONFLICT -> throw SnapshotConflictException(
                code = "SOURCE_ITEM_SCOPE_CONFLICT",
                message = "sourceItemId cannot move to another season",
            )

            CalendarItemApplyOutcome.REVISION_CONFLICT -> throw SnapshotConflictException(
                code = "SOURCE_REVISION_CONFLICT",
                message = "source revision already represents different content",
            )
        }
    }
}

private fun classifyEventReplay(existingHash: String, payloadHash: String): SnapshotIngestionResult {
    if (existingHash == payloadHash) return SnapshotIngestionResult.DUPLICATE
    throw SnapshotConflictException(
        code = "EVENT_ID_CONFLICT",
        message = "eventId was already used for another snapshot",
    )
}

private fun ScheduleSnapshot.toRow(
    acceptedAt: java.time.Instant,
): CalendarItemRow {
    val schedule = schedule
    return CalendarItemRow(
        sourceItemId = sourceItemId,
        seasonId = seasonId,
        revision = revision,
        status = status,
        summary = summary,
        description = description,
        location = location,
        timeType = when (schedule) {
            is ScheduleWindow.UtcInstant -> ScheduleTimeType.UTC_INSTANT
            is ScheduleWindow.ZonedLocal -> ScheduleTimeType.ZONED_LOCAL
        },
        startsAtInstant = (schedule as? ScheduleWindow.UtcInstant)?.start,
        endsAtInstant = (schedule as? ScheduleWindow.UtcInstant)?.end,
        startsAtLocal = (schedule as? ScheduleWindow.ZonedLocal)?.start,
        endsAtLocal = (schedule as? ScheduleWindow.ZonedLocal)?.end,
        zoneId = (schedule as? ScheduleWindow.ZonedLocal)?.zoneId,
        sourceUpdatedAt = sourceUpdatedAt,
        acceptedAt = acceptedAt,
    )
}
