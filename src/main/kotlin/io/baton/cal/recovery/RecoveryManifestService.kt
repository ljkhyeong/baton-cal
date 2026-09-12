package io.baton.cal.recovery

import io.baton.cal.config.CalProperties
import io.baton.cal.persistence.RecoveryManifestRepository
import io.baton.cal.persistence.RecoveryRunCompletionRow
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
        val verifiedSeasonCount = completion?.seasonCount ?: repository.countSeasonManifests(recoveryId)
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
        val expected = request.toState(seasonId)
        val completion = repository.findCompletion(recoveryId)
        if (completion != null) {
            if (repository.findVerifiedSeasonState(recoveryId, seasonId) != expected) {
                throw RecoveryConflictException.runConflict()
            }
            return expected.toResponse(recoveryId)
        }
        requireRecoveryMode()
        seasonLockRepository.acquire(seasonId)
        if (repository.currentSeasonState(seasonId) != expected) {
            throw RecoveryConflictException.manifestMismatch()
        }
        repository.upsertSeasonManifest(
            recoveryId = recoveryId,
            state = expected,
            verifiedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
        return expected.toResponse(recoveryId)
    }

    @Transactional
    fun complete(
        recoveryId: UUID,
        request: RecoveryRunCompletionRequest,
    ): RecoveryRunCompletionResponse {
        repository.lockRecoveryRun(recoveryId)
        repository.findCompletion(recoveryId)?.let { stored ->
            if (stored.seasonCount != request.seasonCount || stored.seasonDigest != request.seasonDigest) {
                throw RecoveryConflictException.runConflict()
            }
            return stored.toResponse()
        }
        requireRecoveryMode()
        repository.lockRecoveryState()
        val states = repository.listVerifiedSeasonStates(recoveryId)
        val verifiedSeasonIds = states.mapTo(mutableSetOf()) { it.seasonId }
        if (
            states.size != request.seasonCount ||
            RecoveryManifestDigest.seasons(states) != request.seasonDigest ||
            repository.currentDataSeasonIds() != verifiedSeasonIds ||
            states.any { repository.currentSeasonState(it.seasonId) != it }
        ) {
            throw RecoveryConflictException.manifestMismatch()
        }
        val completed = RecoveryRunCompletionRow(
            recoveryId = recoveryId,
            seasonCount = request.seasonCount,
            seasonDigest = request.seasonDigest,
            completedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
        repository.insertCompletion(completed)
        return completed.toResponse()
    }

    private fun requireRecoveryMode() {
        if (!properties.recoveryMode) throw RecoveryConflictException.modeRequired()
    }

    private fun RecoveryRunCompletionRow.toResponse() = RecoveryRunCompletionResponse(
        recoveryId = recoveryId,
        result = "COMPLETED",
        seasonCount = seasonCount,
        completedAt = completedAt,
    )

    private fun RecoverySeasonState.toResponse(recoveryId: UUID) = RecoverySeasonManifestResponse(
        recoveryId = recoveryId,
        seasonId = seasonId,
        result = "VERIFIED",
        itemCount = itemCount,
        metadataRevision = metadataRevision,
    )
}
