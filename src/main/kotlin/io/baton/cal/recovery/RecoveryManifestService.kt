package io.baton.cal.recovery

import io.baton.cal.config.CalProperties
import io.baton.cal.persistence.RecoveryManifestRepository
import io.baton.cal.persistence.RecoveryRunCompletionRow
import io.baton.cal.persistence.RecoverySeasonManifestRow
import io.baton.cal.persistence.SeasonProjectionLockRepository
import io.baton.cal.web.InternalResourceNotFoundException
import io.baton.cal.web.RecoveryConflictException
import io.baton.cal.web.RecoveryRunCompletionRequest
import io.baton.cal.web.RecoveryRunCompletionResponse
import io.baton.cal.web.RecoveryRunStatus
import io.baton.cal.web.RecoveryRunStatusResponse
import io.baton.cal.web.RecoverySeasonManifestRequest
import io.baton.cal.web.RecoverySeasonManifestResponse
import io.baton.cal.web.RecoverySeasonStateResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
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
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun getStatus(recoveryId: UUID): RecoveryRunStatusResponse {
        val completion = repository.findCompletion(recoveryId)
        val verifiedSeasonCount = repository.countSeasonManifests(recoveryId)
        if (completion == null && verifiedSeasonCount == 0) {
            throw InternalResourceNotFoundException("저장된 복구 실행을 찾을 수 없습니다")
        }
        return RecoveryRunStatusResponse(
            recoveryId = recoveryId,
            status = if (completion == null) RecoveryRunStatus.IN_PROGRESS else RecoveryRunStatus.COMPLETED,
            recoveryMode = properties.recoveryMode,
            verifiedSeasonCount = verifiedSeasonCount,
            completedAt = completion?.completedAt,
        )
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun getSeasonState(seasonId: UUID): RecoverySeasonStateResponse {
        val state = repository.currentSeasonState(seasonId)
        if (state.itemCount == 0 && state.metadataRevision == null) {
            throw InternalResourceNotFoundException("시즌의 일정과 이름 수신 기록이 없습니다")
        }
        return RecoverySeasonStateResponse(
            seasonId = seasonId,
            itemCount = state.itemCount,
            itemDigest = state.itemDigest,
            metadataRevision = state.metadataRevision,
            metadataDigest = state.metadataDigest,
        )
    }

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
