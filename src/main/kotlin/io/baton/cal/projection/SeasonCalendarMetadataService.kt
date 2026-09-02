package io.baton.cal.projection

import io.baton.cal.persistence.SeasonCalendarMetadataRepository
import io.baton.cal.persistence.SeasonCalendarMetadataRow
import io.baton.cal.persistence.SeasonProjectionLockRepository
import io.baton.cal.web.SeasonCalendarMetadataResponse
import io.baton.cal.web.SnapshotConflictException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class SeasonCalendarMetadataService(
    private val repository: SeasonCalendarMetadataRepository,
    private val lockRepository: SeasonProjectionLockRepository,
    private val projectionService: SeasonProjectionService,
    private val clock: Clock,
) {
    @Transactional
    fun update(seasonId: UUID, revision: Int, displayName: String): SeasonCalendarMetadataResponse {
        lockRepository.acquire(seasonId)
        val current = repository.findBySeasonId(seasonId)
        if (current != null && revision <= current.revision) {
            if (revision == current.revision && displayName != current.displayName) {
                throw SnapshotConflictException(
                    "SEASON_METADATA_REVISION_CONFLICT",
                    "같은 시즌 정보 개정 번호에 다른 표시 이름을 사용할 수 없습니다",
                )
            }
            return current.toResponse()
        }

        val updated = SeasonCalendarMetadataRow(
            seasonId = seasonId,
            revision = revision,
            displayName = displayName,
            acceptedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS),
        )
        repository.upsert(updated)
        if (current?.displayName != displayName) {
            projectionService.rebuildWhileLocked(seasonId)
        }
        return updated.toResponse()
    }
}

private fun SeasonCalendarMetadataRow.toResponse() = SeasonCalendarMetadataResponse(seasonId, revision, displayName)
