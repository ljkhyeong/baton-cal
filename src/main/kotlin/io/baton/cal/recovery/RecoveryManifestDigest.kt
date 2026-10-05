package io.baton.cal.recovery

import io.baton.cal.snapshot.DigestWriter
import java.util.UUID

data class RecoveryItemState(
    val sourceItemId: UUID,
    val revision: Int,
    val payloadDigest: String,
)

data class RecoverySeasonState(
    val seasonId: UUID,
    val itemCount: Int,
    val itemDigest: String,
    val metadataRevision: Int?,
    val metadataDigest: String?,
)

object RecoveryManifestDigest {
    /** 현재 일정 항목과 시즌 이름으로 복구 대조값을 만든다. 이름이 없으면 이름 필드는 `null`이다. */
    fun seasonState(
        seasonId: UUID,
        items: List<RecoveryItemState>,
        metadata: Pair<Int, String>?,
    ) = RecoverySeasonState(
        seasonId = seasonId,
        itemCount = items.size,
        itemDigest = items(items),
        metadataRevision = metadata?.first,
        metadataDigest = metadata?.let { (revision, displayName) -> metadata(revision, displayName) },
    )

    fun items(items: List<RecoveryItemState>): String = DigestWriter.sha256 {
        string("baton-cal-recovery-items-v1")
        items.sortedBy { it.sourceItemId.toString() }.forEach { item ->
            string(item.sourceItemId.toString())
            int(item.revision)
            string(item.payloadDigest)
        }
    }

    fun metadata(revision: Int, displayName: String): String = DigestWriter.sha256 {
        string("baton-cal-recovery-metadata-v1")
        int(revision)
        string(displayName)
    }

    fun seasons(seasons: List<RecoverySeasonState>): String = DigestWriter.sha256 {
        string("baton-cal-recovery-seasons-v1")
        seasons.sortedBy { it.seasonId.toString() }.forEach { season ->
            string(season.seasonId.toString())
            int(season.itemCount)
            string(season.itemDigest)
            nullableInt(season.metadataRevision)
            nullableString(season.metadataDigest)
        }
    }
}
