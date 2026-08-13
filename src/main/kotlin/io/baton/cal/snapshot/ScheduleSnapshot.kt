package io.baton.cal.snapshot

import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleWindow
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

data class ScheduleSnapshot(
    val eventId: UUID,
    val occurredAt: Instant,
    val sourceItemId: UUID,
    val seasonId: UUID,
    val revision: Int,
    val status: CalendarItemStatus,
    val summary: String,
    val description: String?,
    val location: String?,
    val schedule: ScheduleWindow,
    val sourceUpdatedAt: Instant,
)

enum class SnapshotIngestionResult {
    APPLIED,
    DUPLICATE,
    STALE,
}

object SnapshotFingerprint {
    fun sha256(snapshot: ScheduleSnapshot): String {
        val encoded = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeString("baton-cal-snapshot-v1")
                output.writeString(snapshot.sourceItemId.toString())
                output.writeString(snapshot.seasonId.toString())
                output.writeInt(snapshot.revision)
                output.writeString(snapshot.status.name)
                output.writeString(snapshot.summary)
                output.writeNullableString(snapshot.description)
                output.writeNullableString(snapshot.location)
                output.writeString(snapshot.sourceUpdatedAt.toString())

                when (val schedule = snapshot.schedule) {
                    is ScheduleWindow.UtcInstant -> {
                        output.writeString("UTC_INSTANT")
                        output.writeString(schedule.start.toString())
                        output.writeString(schedule.end.toString())
                    }

                    is ScheduleWindow.ZonedLocal -> {
                        output.writeString("ZONED_LOCAL")
                        output.writeString(schedule.start.toString())
                        output.writeString(schedule.end.toString())
                        output.writeString(schedule.zoneId)
                    }
                }
            }
            buffer.toByteArray()
        }

        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(encoded),
        )
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) {
            writeInt(-1)
        } else {
            writeString(value)
        }
    }
}
