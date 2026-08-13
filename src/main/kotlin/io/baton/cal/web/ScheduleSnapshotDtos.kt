package io.baton.cal.web

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.baton.cal.calendar.CalendarItemStatus
import io.baton.cal.calendar.ScheduleWindow
import io.baton.cal.snapshot.ScheduleSnapshot
import io.baton.cal.snapshot.SnapshotIngestionResult
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.hibernate.validator.constraints.Normalized
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

data class ScheduleSnapshotRequest(
    val eventId: UUID,
    val occurredAt: String,
    val sourceItemId: UUID,
    val seasonId: UUID,
    @field:Min(0)
    val revision: Int,
    val status: CalendarItemStatus,
    @field:Normalized(form = Normalizer.Form.NFC)
    @field:Pattern(regexp = "[^\\r]{1,512}")
    val summary: String,
    @field:Normalized(form = Normalizer.Form.NFC)
    @field:Pattern(regexp = "[^\\r]{1,4096}")
    val description: String?,
    @field:Normalized(form = Normalizer.Form.NFC)
    @field:Pattern(regexp = "[^\\r]{1,512}")
    val location: String?,
    val time: ScheduleTimeRequest,
    val sourceUpdatedAt: String,
) {
    fun toDomain(): ScheduleSnapshot = try {
        ScheduleSnapshot(
            eventId = eventId,
            occurredAt = parseInstant(occurredAt),
            sourceItemId = sourceItemId,
            seasonId = seasonId,
            revision = revision,
            status = status,
            summary = summary,
            description = description,
            location = location,
            schedule = time.toDomain(),
            sourceUpdatedAt = parseInstant(sourceUpdatedAt),
        )
    } catch (exception: DateTimeParseException) {
        throw InvalidApiRequestException("snapshot contains an invalid timestamp")
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
    val startInstant: String,
    val endInstant: String,
) : ScheduleTimeRequest {
    override fun toDomain(): ScheduleWindow = ScheduleWindow.UtcInstant(
        parseInstant(startInstant),
        parseInstant(endInstant),
    )
}

data class ZonedLocalTimeRequest(
    val startLocal: String,
    val endLocal: String,
    val zoneId: String,
) : ScheduleTimeRequest {
    override fun toDomain(): ScheduleWindow = ScheduleWindow.ZonedLocal(
        parseLocalDateTime(startLocal),
        parseLocalDateTime(endLocal),
        zoneId,
    )
}

private val LOCAL_DATE_TIME_FORMATTER = strictDateTimeFormatter(caseInsensitive = false)
private val OFFSET_DATE_TIME_FORMATTER = strictDateTimeFormatter(caseInsensitive = true, withOffset = true)

private fun strictDateTimeFormatter(
    caseInsensitive: Boolean,
    withOffset: Boolean = false,
): DateTimeFormatter = DateTimeFormatterBuilder()
    .apply { if (caseInsensitive) parseCaseInsensitive() }
    .appendValue(ChronoField.YEAR, 4)
    .appendLiteral('-')
    .appendValue(ChronoField.MONTH_OF_YEAR, 2)
    .appendLiteral('-')
    .appendValue(ChronoField.DAY_OF_MONTH, 2)
    .appendLiteral('T')
    .appendValue(ChronoField.HOUR_OF_DAY, 2)
    .appendLiteral(':')
    .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
    .appendLiteral(':')
    .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
    .optionalStart()
    .appendLiteral('.')
    .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, false)
    .optionalEnd()
    .apply { if (withOffset) appendOffset("+HH:MM", "Z") }
    .toFormatter(Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)

private fun parseInstant(value: String): Instant = OffsetDateTime
    .parse(value, OFFSET_DATE_TIME_FORMATTER)
    .toInstant()
    .truncatedTo(ChronoUnit.MICROS)

private fun parseLocalDateTime(value: String): LocalDateTime = LocalDateTime
    .parse(value, LOCAL_DATE_TIME_FORMATTER)
    .truncatedTo(ChronoUnit.MICROS)

data class SnapshotIngestionResponse(
    val result: SnapshotIngestionResult,
)
