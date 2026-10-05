package io.baton.cal.support

import org.mockito.ArgumentMatchers

// Mockito 매처는 null을 반환하므로 Kotlin non-null 인자 자리에는 같은 값을 대신 넘긴다. 매칭에는 쓰이지 않는다.
fun <T> eqArg(value: T): T = ArgumentMatchers.eq(value) ?: value

fun <T : Any> anyArg(type: Class<T>, placeholder: T): T = ArgumentMatchers.any(type) ?: placeholder
