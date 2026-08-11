# ADR-0001: BATON CAL 마이크로서비스 경계

- 상태: Accepted
- 결정일: 2026-08-11

## 맥락

BATON은 시즌 timezone, 반복 일정, 운영 회차와 실제 deadline의 권위 있는 원본이다.
Calendar client는 pull, caching, token 폐기와 취소 동기화라는 별도 실패·보안 수명주기를
가진다.

## 결정

BATON CAL을 pull-only iCalendar projection 서비스로 둔다. CAL은 BATON이 확정한 snapshot을
수신하며 recurrence나 deadline을 다시 계산하지 않는다.

CAL은 hashed subscription token, feed projection, 안정적인 `UID`와 `SEQUENCE`, cancellation
tombstone, conditional GET와 rebuild를 소유한다. subscription 발급 가능 여부와 원본 접근
권한은 BATON이 계속 소유한다.

첫 버전에는 provider API를 이용한 양방향 calendar synchronization을 포함하지 않는다.

## 결과

장점:

- calendar pull traffic과 cache lifecycle을 BATON mutation 경로에서 분리한다.
- token 폐기·회전과 calendar compatibility를 독립적으로 운영할 수 있다.
- BATON의 시간 규칙을 단일 source of truth로 유지한다.

비용:

- snapshot event, revision과 cancellation retention 계약이 필요하다.
- secret URL 유출을 고려한 token 보안과 rate limit이 필요하다.
- calendar client별 갱신 지연과 eventual consistency를 받아들여야 한다.

## 보류한 대안

- BATON 내부 `.ics` endpoint: 초기 구현은 단순하지만 pull traffic, token과 cache 정책이
  본체에 결합된다.
- Google/Microsoft provider adapter: 더 풍부하지만 OAuth credential과 양방향 conflict까지
  첫 MVP에 포함해 경계와 운영 위험을 크게 만든다.
