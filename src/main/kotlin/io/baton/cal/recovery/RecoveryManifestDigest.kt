package io.baton.cal.recovery

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
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
    fun items(items: List<RecoveryItemState>): String = digest { output ->
        output.writeString("baton-cal-recovery-items-v1")
        items.sortedBy { it.sourceItemId.toString() }.forEach { item ->
            output.writeString(item.sourceItemId.toString())
            output.writeInt(item.revision)
            output.writeString(item.payloadDigest)
        }
    }

    fun metadata(revision: Int, displayName: String): String = digest { output ->
        output.writeString("baton-cal-recovery-metadata-v1")
        output.writeInt(revision)
        output.writeString(displayName)
    }

    fun seasons(seasons: List<RecoverySeasonState>): String = digest { output ->
        output.writeString("baton-cal-recovery-seasons-v1")
        seasons.sortedBy { it.seasonId.toString() }.forEach { season ->
            output.writeString(season.seasonId.toString())
            output.writeInt(season.itemCount)
            output.writeString(season.itemDigest)
            output.writeNullableInt(season.metadataRevision)
            output.writeNullableString(season.metadataDigest)
        }
    }

    private fun digest(write: (DataOutputStream) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest)).use(write)
        return digest.digest().toHexString()
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) writeInt(-1) else writeString(value)
    }

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        if (value == null) {
            writeBoolean(false)
        } else {
            writeBoolean(true)
            writeInt(value)
        }
    }
}
