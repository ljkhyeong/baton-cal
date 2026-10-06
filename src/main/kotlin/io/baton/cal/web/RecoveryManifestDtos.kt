package io.baton.cal.web

import io.baton.cal.recovery.RecoverySeasonState
import jakarta.validation.constraints.AssertTrue
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

data class RecoverySeasonManifestRequest(
    @field:Min(0)
    val itemCount: Int,
    @field:Pattern(regexp = SHA256_HEX)
    val itemDigest: String,
    @field:Min(0)
    val metadataRevision: Int?,
    @field:Pattern(regexp = SHA256_HEX)
    val metadataDigest: String?,
) {
    /** 시즌 이름 개정 번호와 다이제스트는 함께 전달하거나 함께 생략한다. */
    @get:AssertTrue
    private val isMetadataPaired: Boolean
        get() = (metadataRevision == null) == (metadataDigest == null)

    fun toState(seasonId: UUID) = RecoverySeasonState(seasonId, itemCount, itemDigest, metadataRevision, metadataDigest)
}

data class RecoverySeasonManifestResponse(
    val recoveryId: UUID,
    val seasonId: UUID,
    val itemCount: Int,
    val metadataRevision: Int?,
) {
    val result = "VERIFIED"
}

data class RecoveryRunCompletionRequest(
    @field:Min(0)
    val seasonCount: Int,
    @field:Pattern(regexp = SHA256_HEX)
    val seasonDigest: String,
)

data class RecoveryRunCompletionResponse(
    val recoveryId: UUID,
    val seasonCount: Int,
    val completedAt: Instant,
) {
    val result = "COMPLETED"
}
