package io.baton.cal.web

import io.baton.cal.recovery.RecoverySeasonState
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

enum class RecoveryRunStatus { IN_PROGRESS, COMPLETED }

data class RecoveryRunStatusResponse(
    val recoveryId: UUID,
    val status: RecoveryRunStatus,
    val recoveryMode: Boolean,
    val verifiedSeasonCount: Int,
    val completedAt: Instant?,
)

data class RecoverySeasonStateResponse(
    val seasonId: UUID,
    val itemCount: Int,
    val itemDigest: String,
    val metadataRevision: Int?,
    val metadataDigest: String?,
)

data class RecoverySeasonManifestRequest(
    @field:Min(0)
    val itemCount: Int,
    @field:Pattern(regexp = "[0-9a-f]{64}")
    val itemDigest: String,
    @field:Min(0)
    val metadataRevision: Int?,
    @field:Pattern(regexp = "[0-9a-f]{64}")
    val metadataDigest: String?,
) {
    fun requireValidMetadataPair() {
        if ((metadataRevision == null) != (metadataDigest == null)) {
            throw InvalidApiRequestException("시즌 이름 개정 번호와 다이제스트는 함께 전달해야 합니다")
        }
    }

    fun toState(seasonId: UUID) = RecoverySeasonState(
        seasonId = seasonId,
        itemCount = itemCount,
        itemDigest = itemDigest,
        metadataRevision = metadataRevision,
        metadataDigest = metadataDigest,
    )
}

data class RecoverySeasonManifestResponse(
    val recoveryId: UUID,
    val seasonId: UUID,
    val result: String,
    val itemCount: Int,
    val metadataRevision: Int?,
)

data class RecoveryRunCompletionRequest(
    @field:Min(0)
    val seasonCount: Int,
    @field:Pattern(regexp = "[0-9a-f]{64}")
    val seasonDigest: String,
)

data class RecoveryRunCompletionResponse(
    val recoveryId: UUID,
    val result: String,
    val seasonCount: Int,
    val completedAt: Instant,
)
