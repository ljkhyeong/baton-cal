package io.baton.cal.web

import io.baton.cal.calendar.CalendarItemStatus
import java.time.Instant
import java.util.UUID

data class CalendarItemStatusResponse(
    val sourceItemId: UUID,
    val seasonId: UUID,
    val revision: Int,
    val status: CalendarItemStatus,
    val sourceUpdatedAt: Instant,
)
