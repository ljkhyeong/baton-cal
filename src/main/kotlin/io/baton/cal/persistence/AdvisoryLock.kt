package io.baton.cal.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** advisory lock 키의 해시 시드다. 대상마다 시드를 달리해 같은 UUID라도 다른 대상의 잠금을 기다리지 않는다. */
internal enum class AdvisoryLockScope(val seed: Long) {
    RECOVERY_RUN(0),
    SEASON(1),
}

/** 현재 트랜잭션이 끝날 때 풀리는 PostgreSQL advisory lock을 잡는다. 대기 시간은 `lock_timeout`을 따른다. */
internal fun JdbcClient.lockUntilTransactionEnds(scope: AdvisoryLockScope, id: UUID) {
    sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:id AS TEXT), :seed))")
        .param("id", id.toString())
        .param("seed", scope.seed)
        .query { _, _ -> true }
        .single()
}
