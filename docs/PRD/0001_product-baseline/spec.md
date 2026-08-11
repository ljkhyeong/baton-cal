# PRD-0001: BATON CAL 제품 기준

- 상태: Draft
- 범위: pull-only iCalendar projection MVP

## 문제

BATON 사용자는 시즌 일정, 운영 회차와 실제 마감을 기존 calendar client에서도 확인할
필요가 있다. BATON의 시간 규칙을 외부 client나 새 서비스가 다시 계산하면 timezone과
변경·취소 의미가 서로 달라질 수 있다.

## 목표

- BATON이 확정한 schedule snapshot을 멱등하게 수신한다.
- 폐기 가능한 read-only subscription으로 표준 `.ics` feed를 제공한다.
- 생성, 수정, 취소와 timezone 의미를 안정적인 iCalendar identity로 표현한다.
- conditional GET과 projection rebuild를 지원한다.

## 비목표

- Google/Microsoft Calendar API의 양방향 동기화
- BATON의 recurrence, round 또는 deadline 재계산
- calendar client에서 BATON 원본을 수정하는 기능
- 알림 provider 호출, ROUND grant 발급 또는 GO 링크 생명주기 관리
- workspace key나 Account session을 subscription credential로 재사용

## 핵심 불변식

1. 하나의 BATON calendar item은 lifecycle 전체에서 안정적인 `UID`를 가진다.
2. source revision이 전진할 때만 `SEQUENCE`가 전진한다.
3. 삭제·보관·시즌 종료의 채택된 의미는 필요한 기간 동안 취소 tombstone으로 남는다.
4. token 원문은 persistence, 로그와 metric label에 저장하지 않는다.
5. CAL은 source schedule을 계산하거나 최종 권한을 판정하지 않는다.
6. 동일 projection은 동일한 `ETag`와 calendar representation을 만든다.

## 첫 사용자 흐름

1. 권한 있는 사용자가 BATON에서 calendar subscription 생성을 요청한다.
2. BATON이 권한과 scope를 판단하고 CAL에 opaque provisioning command를 전달한다.
3. CAL은 한 번만 보이는 고엔트로피 token을 발급하고 hash만 저장한다.
4. calendar client가 secret URL을 주기적으로 GET한다.
5. CAL은 현재 projection을 `.ics`로 반환하거나 validator가 같으면 `304`를 반환한다.
6. 사용자는 BATON에서 subscription을 폐기하거나 회전한다.

## MVP 수용 기준

- 같은 snapshot replay가 중복 event를 만들지 않는다.
- out-of-order revision이 최신 projection을 되돌리지 않는다.
- 생성·수정·취소가 안정적인 `UID`와 기대한 `SEQUENCE`로 표현된다.
- DST, 자정, all-day가 아닌 local datetime과 UTC instant fixture를 검증한다.
- 특수문자 escaping, line folding과 content type을 compatibility test로 검증한다.
- token 폐기 직후 기존 URL이 더 이상 feed를 반환하지 않는다.
- BATON 장애와 CAL 장애가 상대 서비스의 source transaction을 rollback하지 않는다.

## 미결정 사항

- feed의 team/season/account scope
- provisioning authentication과 one-time token 반환 계약
- source event envelope와 cancellation retention
- storage와 application 기술 스택
- rate limit, cache policy와 access audit retention
