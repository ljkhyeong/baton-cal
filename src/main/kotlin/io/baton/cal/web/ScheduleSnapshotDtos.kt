package io.baton.cal.web

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.snapshot.ScheduleSnapshot
import io.baton.cal.snapshot.SnapshotIngestionResult
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.hibernate.validator.constraints.CodePointLength
import org.hibernate.validator.constraints.Normalized
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

data class ScheduleSnapshotRequest(
    val eventId: UUID,
    val occurredAt: Instant,
    val sourceItemId: UUID,
    val seasonId: UUID,
    @field:Min(0)
    val revision: Int,
    val status: CalendarItemStatus,
    @field:CodePointLength(min = 1, max = 512)
    @field:Normalized(form = Normalizer.Form.NFC)
    @field:Pattern(regexp = "[^\\r]*")
    val summary: String,
    @field:CodePointLength(min = 1, max = 4_096)
    @field:Normalized(form = Normalizer.Form.NFC)
    @field:Pattern(regexp = "[^\\r]*")
    val description: String?,
    @field:CodePointLength(min = 1, max = 512)
    @field:Normalized(form = Normalizer.Form.NFC)
    @field:Pattern(regexp = "[^\\r]*")
    val location: String?,
    val time: ScheduleTimeRequest,
    val sourceUpdatedAt: Instant,
) {
    fun toDomain(): ScheduleSnapshot = try {
        ScheduleSnapshot(
            eventId = eventId,
            occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS),
            sourceItemId = sourceItemId,
            seasonId = seasonId,
            revision = revision,
            status = status,
            summary = summary,
            description = description,
            location = location,
            schedule = time.toDomain(),
            sourceUpdatedAt = sourceUpdatedAt.truncatedTo(ChronoUnit.MICROS),
        )
    } catch (exception: IllegalArgumentException) {
        throw InvalidApiRequestException(exception.message ?: "snapshot violates the contract")
    }
}

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = UtcInstantTimeRequest::class, name = "UTC_INSTANT"),
    JsonSubTypes.Type(value = ZonedLocalTimeRequest::class, name = "ZONED_LOCAL"),
)
sealed interface ScheduleTimeRequest {
    fun toDomain(): ScheduleWindow
}

data class UtcInstantTimeRequest(
    val startInstant: Instant,
    val endInstant: Instant,
) : ScheduleTimeRequest {
    override fun toDomain(): ScheduleWindow = ScheduleWindow.UtcInstant(
        startInstant.truncatedTo(ChronoUnit.MICROS),
        endInstant.truncatedTo(ChronoUnit.MICROS),
    )
}

data class ZonedLocalTimeRequest(
    val startLocal: LocalDateTime,
    val endLocal: LocalDateTime,
    val zoneId: String,
) : ScheduleTimeRequest {
    override fun toDomain(): ScheduleWindow = ScheduleWindow.ZonedLocal(
        startLocal.truncatedTo(ChronoUnit.MICROS),
        endLocal.truncatedTo(ChronoUnit.MICROS),
        zoneId,
    )
}

data class SnapshotIngestionResponse(
    val result: SnapshotIngestionResult,
)
