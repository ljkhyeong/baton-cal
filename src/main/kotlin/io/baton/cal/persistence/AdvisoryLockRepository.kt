package io.baton.cal.persistence

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 현재 트랜잭션이 끝날 때 풀리는 PostgreSQL advisory lock을 잡는다. 트랜잭션 밖에서는 잠금이 바로 풀리므로
 * 호출자의 트랜잭션을 요구한다. 대기 시간은 `lock_timeout`을 따른다.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class AdvisoryLockRepository(
    private val jdbcClient: JdbcClient,
    meterRegistry: MeterRegistry,
) {
    private val seasonLockTimer: Timer = Timer.builder("baton.cal.projection.lock.acquire")
        .description("시즌 투영 잠금 획득 시간")
        .register(meterRegistry)

    /** 같은 시즌의 일정·이름·투영을 바꾸는 트랜잭션을 직렬화한다. 첫 투영처럼 행이 아직 없는 시즌도 잠근다. */
    fun lockSeason(seasonId: UUID) {
        seasonLockTimer.record(Runnable { lock(SEASON_SEED, seasonId) })
    }

    /** 같은 복구 실행의 시즌 검증과 완료를 직렬화한다. */
    fun lockRecoveryRun(recoveryId: UUID) {
        lock(RECOVERY_RUN_SEED, recoveryId)
    }

    private fun lock(seed: Long, id: UUID) {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:id, :seed))")
            .param("id", id.toString())
            .param("seed", seed)
            .query()
            .singleRow()
    }

    // 대상마다 해시 시드를 달리해 같은 UUID라도 다른 대상의 잠금을 기다리지 않는다.
    private companion object {
        const val RECOVERY_RUN_SEED = 0L
        const val SEASON_SEED = 1L
    }
}
