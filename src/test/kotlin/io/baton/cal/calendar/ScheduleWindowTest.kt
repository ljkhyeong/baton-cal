package io.baton.cal.calendar

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDateTime

class ScheduleWindowTest {
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        // 끝난 DST 규칙이 남아 있어도 지금은 전환이 없는 시간대다.
        "Europe/Moscow, 2027-03-28T02:30:00",
        "Europe/Istanbul, 2027-03-28T03:30:00",
        "America/Asuncion, 2027-10-03T00:30:00",
        "Asia/Damascus, 2027-03-26T00:30:00",
        "Asia/Tokyo, 2026-05-03T00:30:00",
        // 공백 바로 앞 시각과 공백 끝은 존재한다.
        "America/New_York, 2026-03-08T01:59:59",
        "America/New_York, 2026-03-08T03:00:00",
        "Australia/Lord_Howe, 2026-10-04T02:30:00",
    )
    fun `존재하는 현지 시각을 허용한다`(zoneId: String, at: LocalDateTime) {
        assertThatCode { ScheduleWindow.ZonedLocalPoint(at, zoneId).requireExistingLocalTime() }
            .doesNotThrowAnyException()
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "America/New_York, 2026-03-08T02:30:00",
        "America/New_York, 2055-03-14T02:30:00",
        // 반복 규칙 없이 RDATE로만 예고된 미래 전환이다.
        "Africa/Casablanca, 2027-03-14T02:30:00",
        "Asia/Gaza, 2027-03-27T02:30:00",
        // 30분 공백과 겨울을 DST로 정의한 시간대의 공백이다.
        "Australia/Lord_Howe, 2026-10-04T02:15:00",
        "Europe/Dublin, 2027-03-28T01:30:00",
    )
    fun `DST 공백의 현지 시각을 거부한다`(zoneId: String, at: LocalDateTime) {
        assertThatThrownBy { ScheduleWindow.ZonedLocalPoint(at, zoneId).requireExistingLocalTime() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at must not be in a DST gap")
    }

    @Test
    fun `구간의 시작과 종료를 각각 판정한다`() {
        val window = { start: String, end: String ->
            ScheduleWindow.ZonedLocal(LocalDateTime.parse(start), LocalDateTime.parse(end), "America/New_York")
        }
        assertThatThrownBy { window("2026-03-08T02:30:00", "2026-03-08T03:30:00").requireExistingLocalTime() }
            .hasMessageContaining("start must not be in a DST gap")
        assertThatThrownBy { window("2026-03-08T01:30:00", "2026-03-08T02:30:00").requireExistingLocalTime() }
            .hasMessageContaining("end must not be in a DST gap")
    }

    @Test
    fun `저장된 일정을 복원할 때는 현지 시각을 다시 판정하지 않는다`() {
        assertThatCode {
            ScheduleWindow.ZonedLocalPoint(LocalDateTime.parse("2026-03-08T02:30:00"), "America/New_York")
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
