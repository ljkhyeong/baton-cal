package io.baton.cal.web

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlin.enums.enumEntries

/** 결과마다 `result` 태그를 단 카운터를 미리 등록한다. 알림 규칙은 결과별 시계열을 0부터 집계한다. */
internal inline fun <reified E : Enum<E>> MeterRegistry.resultCounters(
    name: String,
    description: String,
): Map<E, Counter> = enumEntries<E>().associateWith { result ->
    Counter.builder(name)
        .description(description)
        .tag("result", result.name.lowercase())
        .register(this)
}
