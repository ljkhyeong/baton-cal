package io.baton.cal.calendar

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class ScheduleWindowTest {
    @Test
    fun `zoned local schedule rejects a wall time inside a DST gap`() {
        assertThatThrownBy {
            ScheduleWindow.ZonedLocal(
                start = LocalDateTime.parse("2026-03-08T02:30:00"),
                end = LocalDateTime.parse("2026-03-08T03:30:00"),
                zoneId = "America/New_York",
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("DST gap")
    }

    @Test
    fun `만료된 과거 DST 규칙은 현재 현지 시각을 거부하지 않는다`() {
        assertThatCode {
            ScheduleWindow.ZonedLocalPoint(
                at = LocalDateTime.parse("2026-05-03T00:30:00"),
                zoneId = "Asia/Tokyo",
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `schedule range must remain positive at iCalendar second precision`() {
        assertThatThrownBy {
            ScheduleWindow.UtcInstant(
                start = Instant.parse("2026-01-01T00:00:00.100Z"),
                end = Instant.parse("2026-01-01T00:00:00.900Z"),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("second precision")
    }
}
