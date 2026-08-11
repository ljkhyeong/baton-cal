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

        inboxRepository.findByEventId(snapshot.eventId)?.let { existing ->
            return existing.classifyEventReplay(payloadHash)
        }

        val existingRevision = inboxRepository.findBySourceItemIdAndRevision(
            snapshot.sourceItemId,
            snapshot.revision,
        )
        if (existingRevision != null && existingRevision.payloadHash != payloadHash) {
            throw SnapshotConflictException(
                code = "SOURCE_REVISION_CONFLICT",
                message = "source revision already represents different content",
            )
        }

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
            return checkNotNull(inboxRepository.findByEventId(snapshot.eventId)) {
                "eventId conflict could not be classified"
            }.classifyEventReplay(payloadHash)
        }
        if (existingRevision != null) return SnapshotIngestionResult.DUPLICATE

        val acceptedAt = projectionService.nextAcceptedAtWhileLocked(snapshot.seasonId, receivedAt)
        val applied = itemRepository.applyIfNewer(snapshot.toRow(payloadHash, acceptedAt))
        return when (applied.outcome) {
            CalendarItemApplyOutcome.APPLIED -> {
                projectionService.rebuildWhileLocked(snapshot.seasonId)
                SnapshotIngestionResult.APPLIED
            }

            CalendarItemApplyOutcome.DUPLICATE -> SnapshotIngestionResult.DUPLICATE
            CalendarItemApplyOutcome.STALE -> SnapshotIngestionResult.STALE
            CalendarItemApplyOutcome.CONFLICT -> {
                val scopeChanged = applied.current.seasonId != snapshot.seasonId
                throw SnapshotConflictException(
                    code = if (scopeChanged) "SOURCE_ITEM_SCOPE_CONFLICT" else "SOURCE_REVISION_CONFLICT",
                    message = if (scopeChanged) {
                        "sourceItemId cannot move to another season"
                    } else {
                        "source revision already represents different content"
                    },
                )
            }
        }
    }
}

private fun SourceEventInboxRow.classifyEventReplay(payloadHash: String): SnapshotIngestionResult {
    if (this.payloadHash == payloadHash) return SnapshotIngestionResult.DUPLICATE
    throw SnapshotConflictException(
        code = "EVENT_ID_CONFLICT",
        message = "eventId was already used for another snapshot",
    )
}

private fun ScheduleSnapshot.toRow(
    payloadHash: String,
    acceptedAt: java.time.Instant,
): CalendarItemRow {
    val schedule = schedule
    return CalendarItemRow(
        sourceItemId = sourceItemId,
        seasonId = seasonId,
        revision = revision,
        payloadHash = payloadHash,
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
