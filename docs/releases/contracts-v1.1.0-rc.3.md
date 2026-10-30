# BATON CAL 계약 1.1.0-rc.3

아래는 `1.1.0-rc.2` 이후 변경 사항이며, 안정 계약 `1.0.0`은 정식 승격 전까지 유지한다.

## 주요 변경

- 기존 JSON Schema와 예시, 복구 다이제스트, iCalendar 골든 바이트는 바꾸지 않았다. 스키마를 지킨 요청의 결과가
  달라지는 경우는 아래 DST 공백 판정뿐이다.
- `POST /internal/api/v1/projections/rebuild`와 `projection-rebuild-all-result.v1` 스키마·예시를 추가했다.
  투영이 저장된 모든 시즌을 시즌마다 따로 커밋해 다시 만들고 처리한 시즌 수와 바이트가 바뀐 시즌 수를 반환한다.
  iCal4j 직렬화나 내장 시간대 데이터를 바꾼 배포 뒤 일정 수신이 없는 시즌도 새 출력을 제공하게 한다.
- 시간대 기준을 iCal4j 4.4.0 내장 Olson `2025a`로 바꿨다. 4.3.0과 내장 시간대 파일·별칭 목록이 같고
  골든 바이트·ETag도 같다.
- `GET /internal/api/v1/subscriptions/{subscriptionId}/activity`와 `subscription-activity.v1` 스키마·예시를
  추가했다. 공개 GET·HEAD·`304`가 성공하면 구독의 최근 조회일을 UTC 날짜로 하루 한 번 기록하고, 회전·폐기 때
  비운다. 조회 시각·IP·클라이언트 정보는 저장하지 않는다. `subscription-status.v1`은 바꾸지 않았다.
- 과거 DB 복원 절차에 복구 모드의 전체 재생성을 추가해 복원된 피드를 현재 버전의 출력에 맞춘다.
- 보존할 운영 데이터가 생기기 전에 CAL의 DB 마이그레이션 V1~V8을 최종 스키마의 V1 하나로 합쳤다.
  PRD-0002에서 V4~V7의 구버전 중지·롤백 금지 순서를 지우고, OCI 스모크 검증 문구를 특정 버전 대신
  체크아웃과 같은 Flyway 마이그레이션 적용으로 바꿨다.
- DST 공백 판정을 `ZoneRulesBuilder` 규칙 대신 피드에 넣는 `VTIMEZONE`의 전환 평가로 바꿨다. 끝난 규칙이 남아 있던
  56개 시간대(`Europe/Moscow`, `Europe/Istanbul` 등)의 존재하는 현지 시각을 `400`으로 거부하던 오판과,
  일회성으로 예고된 미래 전환(`Africa/Casablanca`, `Asia/Gaza` 등)의 공백을 `200`으로 받던 오판을 고쳤다.
  판정은 수신 경로에서만 하므로 시간대 데이터 갱신으로 공백이 된 저장 일정이 시즌의 수신과 재구축을 막지 않는다.
- 스키마보다 넓던 입력 허용을 바로잡았다. `status`의 정수(`1`은 취소로 처리됐다)와 앞뒤 공백, 복구 매니페스트에서
  생략한 `metadataRevision`·`metadataDigest`는 이제 `400 INVALID_REQUEST`다. 공백뿐인 `zoneId`의 오류 메시지가
  `api-error.v1` 길이를 넘던 문제도 고쳤다.
- 내부 API의 모든 응답을 `Cache-Control: no-store`로 맞췄고 없는 내부 경로의 코드를 `RESOURCE_NOT_FOUND`로 바꿨다.
  오류 본문은 `Accept`와 관계없이 JSON이다. 공개 주소 설정의 스킴을 대문자로 써도 `feedUrl`은 스키마대로 소문자다.
- PRD-0002에 `baton-cal-snapshot-v1` 바이트 규칙, `405`·`406`·`415` 오류, 재전송 대상 상태와 대상 밖 조건부 헤더를
  명시하고 서로 어긋나던 재시도·`Last-Modified`·구독 주소 설명을 바로잡았다.
- 구독 세대 기본값 `00000000-0000-0000-0000-000000000001`은 그대로이며 "호환용 초기값" 표현만 "기본값"으로 바꿨다.
- PRD-0002가 게시한 `1.1.0-rc.2`를 미게시로 적은 문장을 바로잡았다.

## 호환성과 사용 조건

`1.1.0-rc.2`를 고정한 BATON은 요청·응답과 다이제스트 계산을 바꿀 필요가 없다. BATON의 교차 서비스 테스트 5개가
이 변경의 CAL 이미지로 통과했다. 전체 재생성은 CAL 운영자가 출력 변경
배포 뒤 호출하며 BATON의 일정 전달에는 필요하지 않다. 최근 조회일 경로는 BATON이 구독 화면에 표시하려 할 때만
이 후보를 고정해 호출한다. 기존 경로의 계약 의미가 같으므로 BATON이 고정 버전을 올릴지는 다음 안정 승격과 함께 정한다.

CAL 쪽에서는 기존 V1~V8이나 `calendar_subscription.last_fetched_on` 추가 전 V1을 적용한 DB가 Flyway 검증에서
실패한다. 운영 DB가 없으므로 로컬·테스트 DB만 다시 만든다.

## 적용 순서

1. 변경을 원격 `main`에 반영하고 필수 CI를 확인한 뒤, 게시를 요청받으면 `contracts-v1.1.0-rc.3` 태그와
   `baton-cal-contracts-1.1.0-rc.3.zip`을 [릴리스 절차](../contract-release-procedure.md)에 따라 게시한다.
2. BATON이 이 버전을 채택하면 공식 태그·ZIP·SHA-256을 고정하고 릴리스·자산 증명을 검증한다.

## 검증 상태와 남은 확인

검증 범위와 로그는 [인수인계](../../HANDOFF.md)에 기록한다. 이 문서는 릴리스 게시나 BATON의 공식 자산 고정을
뜻하지 않는다. 실제 Google Calendar·Apple Calendar·Outlook의 구독·갱신·취소 확인과 운영 연결은 남아 있다.
