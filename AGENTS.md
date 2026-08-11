# BATON CAL Agent Guide

## 시작 순서

- 작업 전에 `HANDOFF.md`, `README.md`와 영향받는 PRD/ADR을 읽는다.
- 구현되지 않은 기능, route, event와 배포 방식을 완료된 것처럼 기록하지 않는다.
- 계약이나 구조를 바꾸면 같은 변경에서 기준 문서를 갱신한다.

## 서비스 경계

- BATON은 timezone, recurrence, schedule, round, deadline과 최종 권한을 소유한다.
- CAL은 확정된 snapshot을 iCalendar로 투영하며 원본 일정을 다시 계산하지 않는다.
- RELAY는 push delivery, GO는 app link, ROUND는 room 참여권을 소유한다.
- 다른 서비스의 데이터베이스, entity, session, workspace key와 credential을 공유하지 않는다.

## 구현 원칙

- source event는 after-commit, at-least-once 전달을 전제로 멱등하게 처리한다.
- iCalendar `UID`는 안정적이어야 하고 source 변경은 단조 증가하는 revision을 통해
  `SEQUENCE`에 반영한다.
- 삭제를 즉시 망각하지 않고 calendar client가 관측할 수 있는 cancellation tombstone으로
  표현한다.
- timezone은 IANA zone과 명시적인 instant/local 의미를 보존한다.
- 시간 의존 코드는 `Clock`을 주입하고 DST·자정 경계를 fixed-clock 테스트로 검증한다.
- subscription token 원문, workspace key, session과 grant를 저장·로그·metric label에 넣지
  않는다.
- 조건부 GET의 validator는 동일 projection에서 결정적으로 계산한다.

## 문서와 검증

- 제품 동작은 `docs/PRD/`, 장기 구조 결정은 `docs/ADR/`에 기록한다.
- calendar compatibility test에는 생성, 수정, 취소, timezone과 escaping을 포함한다.
- projection test에는 duplicate, out-of-order, rebuild와 token rotation을 포함한다.
- 실제 실행 명령이 생기기 전에는 존재하지 않는 build나 test 명령을 기록하지 않는다.

## Git

- 커밋 제목은 `종류: 한글 요약` 형식을 사용한다.
- 종류는 `기능`, `수정`, `문서`, `테스트`, `설정`, `리팩터` 중에서 고른다.
- 기능 작업 브랜치는 기본적으로 `codex/` prefix를 사용한다.
