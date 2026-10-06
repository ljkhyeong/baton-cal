package io.baton.cal.support

import io.baton.cal.persistence.SeasonFeedProjectionRow
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 저장된 시즌 피드 투영을 읽는다. 운영 저장소에는 시즌별 본문 조회가 없다. */
fun JdbcClient.feedProjection(seasonId: UUID): SeasonFeedProjectionRow = sql(
    "SELECT season_id, representation, etag, last_modified FROM season_feed_projection WHERE season_id = :seasonId",
).param("seasonId", seasonId).query(SeasonFeedProjectionRow::class.java).single()

/** 두 작업을 동시에 실행하고 제출 순서대로 결과를 반환한다. 시간 안에 끝나지 않으면 실패한다. */
fun <T> runConcurrently(first: () -> T, second: () -> T): List<T> = Executors.newFixedThreadPool(2).use { executor ->
    executor.invokeAll(listOf(Callable(first), Callable(second)), 10, TimeUnit.SECONDS).map { it.get() }
}

/**
 * 한 트랜잭션이 [lock]으로 잠금을 잡은 동안 다른 스레드에서 짧은 lock_timeout으로 [request]를 실행하고 그 트랜잭션은
 * 롤백한다. [request] 안의 Spring 트랜잭션은 같은 스레드의 요청 트랜잭션에 참여하므로 lock_timeout이 적용된다.
 */
fun TransactionTemplate.whileLocked(jdbcClient: JdbcClient, lock: () -> Unit, request: () -> Unit) {
    Executors.newSingleThreadExecutor().use { executor ->
        executeWithoutResult {
            lock()
            executor.submit {
                executeWithoutResult { rollback ->
                    rollback.setRollbackOnly()
                    jdbcClient.sql("SET LOCAL lock_timeout TO '200ms'").update()
                    request()
                }
            }.get(5, TimeUnit.SECONDS)
        }
    }
}
