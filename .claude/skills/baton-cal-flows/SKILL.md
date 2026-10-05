---
name: baton-cal-flows
description: BATON CAL의 일정 수신, 캘린더 투영, 구독 토큰, 복구, DB 스키마, HTTP 계약을 변경할 때 사용하는 개발 지침. 문서·운영 구성·의존성만 바꾸는 작업은 baton-cal-docs, baton-cal-operations, baton-cal-dependencies를 사용한다.
---

# BATON CAL 개발

공통 규칙은 `AGENTS.md`, 현재 상태는 `HANDOFF.md`를 따른다. 경로는 저장소 루트 기준이다.
긴 문서는 `rg -n`으로 위치를 찾고 필요한 절만 읽는다. 코드·테스트 위치는 [코드 지도](references/code-map.md)를 본다.

## 작업별 기준 문서

| 변경 대상 | 읽을 문서 |
| --- | --- |
| 제품 범위·서비스 역할 | `docs/PRD/0001_product-baseline/spec.md`, `docs/ADR/0001_microservice-boundary/adr.md` |
| 일정·구독·복구 HTTP 계약 | `docs/PRD/0002_mvp-contract/spec.md`의 해당 절, `contracts/README.md`의 `기준과 버전 관리` |
| 시간대·직렬화·DB·인증·로그 경계 | `docs/ADR/0002_technology-stack/adr.md`의 해당 절 |
| 계층 규칙·자동 검사 범위 | `docs/ADR/0003_agent-feedback/adr.md` |
| 배포·과거 DB 복원·내부 Bearer 회전 | `README.md`의 `운영 기본 설정` 하위 절, `docs/operations.md` |
| 외부 캘린더 안내·성능 | `docs/calendar-subscription-guide.md`, `docs/performance-baseline.md` |
| 계약 버전·공식 릴리스 | baton-cal-contract-release 스킬 |

## 변경 시 주의점

- HTTP DTO는 웹 계층, 트랜잭션은 서비스, 상태 규칙은 도메인, SQL·잠금·CAS는 영속성 계층에서 처리한다.
  상태·필드 변경은 관련 분기·SQL·행 매핑·스키마·픽스처까지 확인한다.
- 시간 의존 코드는 `config/TimeConfiguration.kt`의 `Clock` 빈을 주입받는다. `Instant.now()` 같은 직접 호출을
  추가하지 않는다. 시점에 임의 지속 시간을 만들거나 종일 날짜를 자정 시각으로 바꾸지 않는다. 종일 종료 날짜는 배타적이다.
- 입력 문자열 필드의 숫자·불리언 강제 변환은 `config/JacksonConfiguration.kt`에서 막는다. 새 입력 필드는
  `SnapshotInputContractTest`처럼 거부 사례를 HTTP 테스트에 남긴다.
- 마이그레이션(`src/main/resources/db/migration/V*.sql`)은 운영 데이터가 생기기 전까지 `V1`을 직접 고치고
  로컬 DB를 다시 만든다. 운영 배포 뒤에는 적용된 파일을 수정하지 않고 다음 번호로 추가하며, 제약 강화·열 삭제는
  기존 데이터 이전 방법과 구버전 실행·롤백 가능 여부를 정하고 기존 스키마에서 갱신하는 경로를 통합 테스트로 검증한다.
- iCal4j의 시간대 데이터로 입력 검증과 출력을 맞춘다. `IcsCalendarRenderer`의 줄 접기 길이 25, 명시적
  `PropertyList`와 빈 피드 처리에는 이유가 있다. 렌더러·골든 테스트·ADR을 확인한 뒤 바꾼다.
  출력 바이트가 바뀌면 골든과 `ETag` 차이를 검토하며 골든을 테스트에서 자동으로 덮어쓰지 않는다.
- 설정 바인딩 실패가 비밀값을 로그에 남길 수 있다. 내부 토큰 검증 오류 메시지에는 값을 넣지 않는다.
- 오류 응답의 `Cache-Control: no-store`와 DB 일시 장애의 `503 SERVICE_BUSY`·재시도 간격을 유지한다.
- 공개 피드는 `ACTIVE`·토큰 해시·외부 런타임 구독 세대가 일치해야 한다. 과거 DB 복원 전 모든 인스턴스를
  중지하고 사용한 적 없는 non-NIL 세대로 바꾼다. 복구 모드는 새 발급을 막으며 과거 URL 무효화를 대신하지 않는다.
- 복구 완료는 BATON의 최신 일정·취소·시즌 이름과 전체 시즌 집합을 대조한 뒤 기록한다. 완료 재요청은
  최초 완료 시각을 유지하며, 완료 기록만으로 런타임 복구 모드를 자동 해제하지 않는다.
- 계약을 바꾸면 PRD와 스키마·예시·골든 중 영향받는 파일을 함께 갱신한다. 게시된 계약 입력
  (`contracts/**`, 루트 `LICENSE`, PRD-0002)을 바꾸면 `contracts/VERSION`을 다음 버전으로 올린다.

## 진행 순서

1. `HANDOFF.md`의 최근 검증 커밋과 현재 작업 트리를 `git diff <검증 커밋>`으로 비교한다.
2. 편집 전에 `python3 -B scripts/agent_feedback.py start --session <작업명>`을 실행한다.
   Claude Code에는 이 저장소의 훅이 없으므로 파일 작성 후 `files`, 종료 전 `final`도 같은 세션으로 직접 실행한다.
3. 영향 범위가 넓으면 Explore 서브에이전트로 호출 위치를 찾고, 수정할 파일은 직접 읽는다.
4. 계약 영향이 있으면 PRD → 스키마·예시·골든 → 코드 → 테스트 순으로 맞춘다.
5. 코드 지도의 관련 테스트부터 실행하고, 공통 코드나 여러 계층을 바꿨다면 `check`로 넓힌다.
6. baton-cal-review로 전체 diff를 검토하고 baton-cal-finish로 마무리한다.

## 검증

검증 선택·재실행·종료 기준과 명령은 `docs/development.md`를 따른다. DB 통합 테스트에는 Docker가 필요하다.
실패는 제품·테스트·환경 원인으로 분류한 뒤 해당 범위부터 재실행한다. `UP-TO-DATE`·`FROM-CACHE`는 기존 결과
재사용으로 보고한다. 로컬 검증을 공식 릴리스·실제 운영·외부 앱 검증으로 확대해서 보고하지 않는다.
