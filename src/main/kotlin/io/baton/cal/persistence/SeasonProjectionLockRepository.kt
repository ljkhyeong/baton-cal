package io.baton.cal.persistence

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.UUID
import org.springframework.dao.CannotAcquireLockException
import org.springframework.jdbc.UncategorizedSQLException
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
     * 잠금 행이 없으면 만들고 PostgreSQL 행 잠금을 건다.
     * 호출자는 캘린더 항목과 시즌 피드를 갱신하는 트랜잭션 안에서 실행해야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun acquire(seasonId: UUID) {
        try {
            acquisitionTimer.record(Runnable {
                jdbcClient.sql(
                    """
                    INSERT INTO season_projection_lock (season_id)
                    VALUES (:seasonId)
                    ON CONFLICT (season_id) DO NOTHING
                    """.trimIndent(),
                )
                    .param("seasonId", seasonId)
                    .update()

                jdbcClient.sql(
                    """
                    SELECT season_id
                    FROM season_projection_lock
                    WHERE season_id = :seasonId
                    FOR UPDATE
                    """.trimIndent(),
                )
                    .param("seasonId", seasonId)
                    .query(UUID::class.java)
                    .single()
            })
        } catch (exception: UncategorizedSQLException) {
            if (exception.sqlException?.sqlState != LOCK_NOT_AVAILABLE_SQL_STATE) throw exception
            throw CannotAcquireLockException("시즌 투영 잠금 획득 제한 시간을 초과했습니다", exception)
        }
    }

    private companion object {
        const val LOCK_NOT_AVAILABLE_SQL_STATE = "55P03"
    }
}
