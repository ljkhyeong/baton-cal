package io.baton.cal.persistence

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
class SeasonProjectionLockRepository(
    private val jdbcClient: JdbcClient,
    meterRegistry: MeterRegistry,
) {
    private val acquisitionTimer: Timer = Timer.builder("baton.cal.projection.lock.acquire")
        .description("시즌 투영 잠금 획득 시간")
        .register(meterRegistry)

    /**
     * 같은 시즌의 일정·이름·투영을 바꾸는 트랜잭션을 직렬화한다. 첫 투영처럼 행이 아직 없는 시즌도 잠근다.
     * 호출자는 캘린더 항목과 시즌 피드를 갱신하는 트랜잭션 안에서 실행해야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun acquire(seasonId: UUID) {
        acquisitionTimer.record(Runnable { jdbcClient.lockUntilTransactionEnds(AdvisoryLockScope.SEASON, seasonId) })
    }
}
