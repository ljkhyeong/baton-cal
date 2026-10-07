package io.baton.cal.recovery

import io.baton.cal.config.CalProperties
import io.baton.cal.persistence.RecoveryManifestRepository
import io.baton.cal.persistence.RecoveryRunCompletionRow
import io.baton.cal.persistence.SeasonCalendarMetadataRepository
import io.baton.cal.persistence.AdvisoryLockRepository
import io.baton.cal.web.resourceNotFound
import io.baton.cal.web.conflict
import io.baton.cal.web.RecoveryRunCompletionResponse
import io.baton.cal.web.RecoveryRunStatus
import io.baton.cal.web.RecoveryRunStatusResponse
import io.baton.cal.web.RecoverySeasonManifestResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class RecoveryManifestService(
    private val repository: RecoveryManifestRepository,
    private val metadataRepository: SeasonCalendarMetadataRepository,
    private val lockRepository: AdvisoryLockRepository,
    private val properties: CalProperties,
    private val clock: Clock,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun getStatus(recoveryId: UUID): RecoveryRunStatusResponse {
        val completion = repository.findCompletion(recoveryId)
        val verifiedSeasonCount = completion?.seasonCount ?: repository.listVerifiedSeasonStates(recoveryId).size
        if (completion == null && verifiedSeasonCount == 0) {
            throw resourceNotFound("저장된 복구 실행을 찾을 수 없습니다")
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
    fun getSeasonState(seasonId: UUID): RecoverySeasonState {
        val state = currentSeasonState(seasonId)
        if (state.itemCount == 0 && state.metadataRevision == null) {
            throw resourceNotFound("시즌의 일정과 이름 수신 기록이 없습니다")
        }
        return state
    }

    @Transactional
    fun verifySeason(recoveryId: UUID, expected: RecoverySeasonState): RecoverySeasonManifestResponse {
        val seasonId = expected.seasonId
        lockRepository.lockRecoveryRun(recoveryId)
        val completion = repository.findCompletion(recoveryId)
        if (completion != null) {
            if (repository.findVerifiedSeasonState(recoveryId, seasonId) != expected) throw runConflict()
            return expected.toResponse(recoveryId)
        }
        requireRecoveryMode()
        lockRepository.lockSeason(seasonId)
        if (currentSeasonState(seasonId) != expected) {
            throw manifestMismatch()
        }
        repository.upsertSeasonManifest(
            recoveryId = recoveryId,
            state = expected,
            verifiedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
        return expected.toResponse(recoveryId)
    }

    @Transactional
    fun complete(recoveryId: UUID, seasonCount: Int, seasonDigest: String): RecoveryRunCompletionResponse {
        lockRepository.lockRecoveryRun(recoveryId)
        repository.findCompletion(recoveryId)?.let { stored ->
            if (stored.seasonCount != seasonCount || stored.seasonDigest != seasonDigest) {
                throw runConflict()
            }
            return stored.toResponse()
        }
        requireRecoveryMode()
        repository.lockRecoveryState()
        val states = repository.listVerifiedSeasonStates(recoveryId)
        val verifiedSeasonIds = states.mapTo(mutableSetOf()) { it.seasonId }
        if (
            states.size != seasonCount ||
            RecoveryManifestDigest.seasons(states) != seasonDigest ||
            repository.currentDataSeasonIds() != verifiedSeasonIds ||
            states.any { currentSeasonState(it.seasonId) != it }
        ) {
            throw manifestMismatch()
        }
        val completed = RecoveryRunCompletionRow(
            recoveryId = recoveryId,
            seasonCount = seasonCount,
            seasonDigest = seasonDigest,
            completedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
        )
        repository.insertCompletion(completed)
        return completed.toResponse()
    }

    private fun currentSeasonState(seasonId: UUID): RecoverySeasonState {
        val metadata = metadataRepository.findBySeasonId(seasonId)
        return RecoveryManifestDigest.seasonState(
            seasonId,
            repository.listItemStates(seasonId),
            metadata?.let { it.revision to it.displayName },
        )
    }

    private fun requireRecoveryMode() {
        if (!properties.recoveryMode) {
            throw conflict("RECOVERY_MODE_REQUIRED", "recovery manifest verification requires recovery mode")
        }
    }

    private fun manifestMismatch() = conflict(
        code = "RECOVERY_MANIFEST_MISMATCH",
        message = "recovery manifest does not match the current calendar state",
    )

    private fun runConflict() = conflict(
        code = "RECOVERY_RUN_CONFLICT",
        message = "recoveryId already represents another manifest",
    )

    private fun RecoveryRunCompletionRow.toResponse() = RecoveryRunCompletionResponse(
        recoveryId = recoveryId,
        seasonCount = seasonCount,
        completedAt = completedAt,
    )

    private fun RecoverySeasonState.toResponse(recoveryId: UUID) = RecoverySeasonManifestResponse(
        recoveryId = recoveryId,
        seasonId = seasonId,
        itemCount = itemCount,
        metadataRevision = metadataRevision,
    )
}
