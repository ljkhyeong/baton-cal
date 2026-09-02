package io.baton.cal.recovery

import io.baton.cal.config.CalProperties
import io.baton.cal.persistence.RecoveryManifestRepository
import io.baton.cal.persistence.RecoveryRunCompletionRow
import io.baton.cal.persistence.RecoverySeasonManifestRow
import io.baton.cal.persistence.SeasonProjectionLockRepository
import io.baton.cal.web.RecoveryConflictException
import io.baton.cal.web.RecoveryRunCompletionRequest
import io.baton.cal.web.RecoveryRunCompletionResponse
import io.baton.cal.web.RecoverySeasonManifestRequest
import io.baton.cal.web.RecoverySeasonManifestResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class RecoveryManifestService(
    private val repository: RecoveryManifestRepository,
    private val seasonLockRepository: SeasonProjectionLockRepository,
    private val properties: CalProperties,
    private val clock: Clock,
) {
    @Transactional
    fun verifySeason(
        recoveryId: UUID,
        seasonId: UUID,
        request: RecoverySeasonManifestRequest,
    ): RecoverySeasonManifestResponse {
        request.requireValidMetadataPair()
        repository.lockRecoveryRun(recoveryId)
        seasonLockRepository.acquire(seasonId)
        val expected = request.toState(seasonId)
        val stored = repository.findSeasonManifest(recoveryId, seasonId)
        val completion = repository.findCompletion(recoveryId)
        if (completion != null) {
            if (stored?.state() != expected) throw RecoveryConflictException.runConflict()
            return stored.toResponse()
        }
        requireRecoveryMode()
        if (repository.currentSeasonState(seasonId) != expected) {
            throw RecoveryConflictException.manifestMismatch()
        }
        val row = RecoverySeasonManifestRow(
            recoveryId = recoveryId,
            seasonId = seasonId,
            itemCount = expected.itemCount,
            itemDigest = expected.itemDigest,
            metadataRevision = expected.metadataRevision,
            metadataDigest = expected.metadataDigest,
            verifiedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
        repository.upsertSeasonManifest(row)
        return row.toResponse()
    }

    @Transactional
    fun complete(
        recoveryId: UUID,
        request: RecoveryRunCompletionRequest,
    ): RecoveryRunCompletionResponse {
        repository.lockRecoveryRun(recoveryId)
        repository.findCompletion(recoveryId)?.let { return completionResponse(it, request) }
        requireRecoveryMode()
        repository.lockRecoveryState()
        val manifests = repository.listSeasonManifests(recoveryId)
        val states = manifests.map { it.state() }
        val verifiedSeasonIds = states.mapTo(mutableSetOf()) { it.seasonId }
        if (
            manifests.size != request.seasonCount ||
            RecoveryManifestDigest.seasons(states) != request.seasonDigest ||
            repository.currentDataSeasonIds() != verifiedSeasonIds ||
            states.any { repository.currentSeasonState(it.seasonId) != it }
        ) {
            throw RecoveryConflictException.manifestMismatch()
        }
        val completed = repository.insertCompletion(
            RecoveryRunCompletionRow(
                recoveryId = recoveryId,
                seasonCount = request.seasonCount,
                seasonDigest = request.seasonDigest,
                completedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
            ),
        )
        return completionResponse(completed, request)
    }

    private fun requireRecoveryMode() {
        if (!properties.recoveryMode) throw RecoveryConflictException.modeRequired()
    }

    private fun completionResponse(
        row: RecoveryRunCompletionRow,
        request: RecoveryRunCompletionRequest,
    ): RecoveryRunCompletionResponse {
        if (row.seasonCount != request.seasonCount || row.seasonDigest != request.seasonDigest) {
            throw RecoveryConflictException.runConflict()
        }
        return RecoveryRunCompletionResponse(
            recoveryId = row.recoveryId,
            result = "COMPLETED",
            seasonCount = row.seasonCount,
            completedAt = row.completedAt,
        )
    }

    private fun RecoverySeasonManifestRow.toResponse() = RecoverySeasonManifestResponse(
        recoveryId = recoveryId,
        seasonId = seasonId,
        result = "VERIFIED",
        itemCount = itemCount,
        metadataRevision = metadataRevision,
    )
}
