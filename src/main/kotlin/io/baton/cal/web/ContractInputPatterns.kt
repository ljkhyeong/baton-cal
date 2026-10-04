package io.baton.cal.web

// 계약 TEXT 필드가 허용하는 한 글자다. 길이 상한은 정규식 수량자로 붙여 JSON Schema처럼 코드 포인트로 센다.
internal const val CONTRACT_TEXT_CHARACTER = "[^\\x{0}-\\x{8}\\x{B}-\\x{1F}\\x{7F}\\x{D800}-\\x{DFFF}]"

internal const val SHA256_HEX = "[0-9a-f]{64}"
