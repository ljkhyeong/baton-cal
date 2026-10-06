package io.baton.cal.snapshot

import io.baton.cal.persistence.CalendarItemRepository
import io.baton.cal.persistence.CalendarItemRow
import io.baton.cal.persistence.AdvisoryLockRepository
import io.baton.cal.persistence.SourceEventInboxRepository
import io.baton.cal.projection.SeasonProjectionService
import io.baton.cal.web.resourceNotFound
import io.baton.cal.web.conflict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class SnapshotIngestionService(
    private val inboxRepository: SourceEventInboxRepository,
    private val itemRepository: CalendarItemRepository,
    private val lockRepository: AdvisoryLockRepository,
    private val projectionService: SeasonProjectionService,
    private val clock: Clock,
) {
    fun getItemStatus(sourceItemId: UUID): AcceptedItemStatus =
        itemRepository.findStatusBySourceItemId(sourceItemId)
            ?: throw resourceNotFound("일정 항목을 찾을 수 없습니다")

    @Transactional
    fun ingest(snapshot: ScheduleSnapshot): SnapshotIngestionResult = ingestBatch(listOf(snapshot)).single()

    @Transactional
    fun ingestBatch(snapshots: List<ScheduleSnapshot>): List<SnapshotIngestionResult> {
        // 여러 시즌을 받는 요청도 항상 같은 순서로 잠가 서로 기다리는 상황을 줄인다.
        snapshots.map { it.seasonId }.distinct().sorted().forEach(lockRepository::lockSeason)
        val changedSeasons = linkedSetOf<UUID>()
        val results = snapshots.map { snapshot ->
            applySnapshot(snapshot).also { result ->
                if (result == SnapshotIngestionResult.APPLIED) changedSeasons.add(snapshot.seasonId)
            }
        }
        changedSeasons.forEach(projectionService::rebuildWhileLocked)
        return results
    }

    private fun applySnapshot(snapshot: ScheduleSnapshot): SnapshotIngestionResult {
        val payloadHash = SnapshotFingerprint.sha256(snapshot)
        val receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)

        val inserted = inboxRepository.insert(snapshot, payloadHash, receivedAt)
        if (!inserted) {
            if (inboxRepository.getPayloadHashByEventId(snapshot.eventId) == payloadHash) {
                return SnapshotIngestionResult.DUPLICATE
            }
            throw conflict(
                code = "EVENT_ID_CONFLICT",
                message = "eventId was already used for another snapshot",
            )
        }

        val existingRevisionHash = inboxRepository.findPayloadHashBySourceItemIdAndRevision(
            snapshot.sourceItemId,
            snapshot.revision,
            snapshot.eventId,
        )
        if (existingRevisionHash != null) {
            if (existingRevisionHash == payloadHash) return SnapshotIngestionResult.DUPLICATE
            throw revisionConflict()
        }

        val acceptedAt = receivedAt.truncatedTo(ChronoUnit.SECONDS)
        if (itemRepository.upsertIfNewer(CalendarItemRow.from(snapshot, acceptedAt))) {
            return SnapshotIngestionResult.APPLIED
        }

        val current = checkNotNull(itemRepository.findStatusBySourceItemId(snapshot.sourceItemId))
        return when {
            current.seasonId != snapshot.seasonId -> throw conflict(
                code = "SOURCE_ITEM_SCOPE_CONFLICT",
                message = "sourceItemId cannot move to another season",
            )

            snapshot.revision < current.revision -> SnapshotIngestionResult.STALE
            else -> throw revisionConflict()
        }
    }

    private fun revisionConflict() = conflict(
        code = "SOURCE_REVISION_CONFLICT",
        message = "source revision already represents different content",
    )
}
