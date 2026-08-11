# BATON CAL

BATON CAL은 BATON이 확정한 시즌 일정, 운영 회차와 마감을 읽기 전용 iCalendar feed로
투영하는 독립 서비스다.

> 현재 상태: 문서 중심 프로젝트 스캐폴드다. production code, API와 기술 스택은 아직
> 없다. 공개 저장소는 [ljkhyeong/baton-cal](https://github.com/ljkhyeong/baton-cal)이다.

## 서비스 경계

CAL이 소유한다.

- 원문을 저장하지 않는 hashed subscription token과 폐기·회전 lifecycle
- BATON schedule snapshot의 calendar projection
- 안정적인 iCalendar `UID`, `SEQUENCE`와 cancellation tombstone
- `.ics` feed, `ETag`, `Last-Modified`와 conditional GET
- source event inbox, 멱등성, replay와 projection rebuild 상태
- feed 조회의 최소 운영 관측

CAL이 소유하지 않는다.

- 시즌 timezone, recurrence, 회차 생성과 실제 deadline 계산: BATON
- AccountMembership, feed 발급·폐기 권한과 최종 접근 판단: BATON
- 이메일·메시지와 provider retry: BATON RELAY
- 공개 app link의 코드·만료·폐기: BATON GO
- ROUND room 참여 자격과 participation grant: BATON과 ROUND

## 첫 MVP

1. BATON이 after-commit으로 전달한 확정 schedule snapshot만 수신한다.
2. team 또는 season 범위의 폐기 가능한 read-only subscription을 만든다.
3. 안정적인 `UID`, source revision 기반 `SEQUENCE`와 취소 event를 가진 `.ics`를 제공한다.
4. `ETag`와 `Last-Modified`로 calendar client의 반복 pull을 효율적으로 처리한다.
5. exact replay, out-of-order update, token rotation과 전체 rebuild를 검증한다.

## 보안 원칙

- workspace key, Account session, ROUND grant와 provider credential을 calendar URL에 넣지 않는다.
- subscription token 원문은 저장하지 않고 로그나 metric label에 남기지 않는다.
- calendar description에는 최소 정보와 권한 없는 locator만 포함한다.
- calendar client의 pull은 BATON의 권한 판단을 우회하지 않는다.

## 문서

- [제품 기준](docs/PRD/0001_product-baseline/spec.md)
- [마이크로서비스 경계](docs/ADR/0001_microservice-boundary/adr.md)
- [다음 작업](HANDOFF.md)

## 기술 스택

아직 결정하지 않았다. iCalendar compatibility, token 보안, conditional GET와 rebuild 계약을
먼저 확정한 뒤 선택한다.
