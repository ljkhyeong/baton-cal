package io.baton.cal.web

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.snapshot.ScheduleSnapshot
import io.baton.cal.snapshot.SnapshotIngestionResult
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.hibernate.validator.constraints.CodePointLength
import org.hibernate.validator.constraints.Normalized
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

data class ScheduleSnapshotRequest(
    val eventId: UUID,
    @field:Pattern(regexp = INSTANT_PATTERN)
    val occurredAt: String,
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
    @field:Valid
    val time: ScheduleTimeRequest,
    @field:Pattern(regexp = INSTANT_PATTERN)
    val sourceUpdatedAt: String,
) {
    fun toDomain(): ScheduleSnapshot = try {
        ScheduleSnapshot(
            eventId = eventId,
            occurredAt = Instant.parse(occurredAt).truncatedTo(ChronoUnit.MICROS),
            sourceItemId = sourceItemId,
            seasonId = seasonId,
            revision = revision,
            status = status,
            summary = summary,
            description = description,
            location = location,
            schedule = time.toDomain(),
            sourceUpdatedAt = Instant.parse(sourceUpdatedAt).truncatedTo(ChronoUnit.MICROS),
        )
    } catch (exception: DateTimeParseException) {
        throw InvalidApiRequestException(exception.message ?: "snapshot contains an invalid timestamp")
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
    @field:Pattern(regexp = INSTANT_PATTERN)
    val startInstant: String,
    @field:Pattern(regexp = INSTANT_PATTERN)
    val endInstant: String,
) : ScheduleTimeRequest {
    override fun toDomain(): ScheduleWindow = ScheduleWindow.UtcInstant(
        Instant.parse(startInstant).truncatedTo(ChronoUnit.MICROS),
        Instant.parse(endInstant).truncatedTo(ChronoUnit.MICROS),
    )
}

data class ZonedLocalTimeRequest(
    @field:Pattern(regexp = LOCAL_SECOND_PATTERN)
    val startLocal: String,
    @field:Pattern(regexp = LOCAL_SECOND_PATTERN)
    val endLocal: String,
    val zoneId: String,
) : ScheduleTimeRequest {
    override fun toDomain(): ScheduleWindow = ScheduleWindow.ZonedLocal(
        LocalDateTime.parse(startLocal).truncatedTo(ChronoUnit.MICROS),
        LocalDateTime.parse(endLocal).truncatedTo(ChronoUnit.MICROS),
        zoneId,
    )
}

private const val LOCAL_SECOND_PATTERN =
    "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?"
private const val INSTANT_PATTERN =
    "[0-9]{4}-[0-9]{2}-[0-9]{2}[tT][0-9]{2}:[0-9]{2}:[0-9]{2}" +
        "(?:\\.[0-9]{1,9})?(?:[zZ]|[+-][0-9]{2}:[0-9]{2})"

data class SnapshotIngestionResponse(
    val result: SnapshotIngestionResult,
)
