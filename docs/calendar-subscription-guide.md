# 캘린더 앱 구독 안내와 호환성 확인

## 사용자 안내

BATON에서 발급한 `https://cal.b4ton.com/calendars/v1/...ics` 주소를 캘린더 앱의 **URL 구독**에
등록한다. 파일로 내려받아 가져오면 이후 일정 변경·취소가 자동 반영되지 않을 수 있다.
구독은 읽기 전용이며 일정 변경과 참여 권한은 BATON에서 관리한다.

구독 주소를 아는 사람은 해당 피드를 읽을 수 있으므로 게시판·메신저·오류 제보에 붙이지 않는다.
분실했거나 다른 사람에게 전달했다면 BATON에서 새 주소를 발급받고 기존 구독을 제거한 뒤
새 주소를 등록한다. 서버에서 이전 주소를 막아도 앱이 이미 내려받은 일정은 화면에 남을 수 있다.

| 앱 | 등록 방법 | 갱신 안내 |
| --- | --- | --- |
| Google Calendar 웹 | 컴퓨터에서 다른 캘린더 옆 `+` → URL로 추가 → 구독 주소 입력 | Google의 수집 주기에 따라 반영된다. 즉시 반영을 약속하지 않는다. 모바일에만 등록 메뉴가 없으면 웹에서 먼저 등록한다. |
| Apple Calendar Mac | 파일 → 새로운 캘린더 구독 → 주소 입력 → 구독 | 캘린더 정보의 자동 새로고침 주기를 선택한다. 수동 새로고침 결과도 확인할 수 있다. |
| Outlook 웹 | 캘린더 추가 → 웹에서 구독 → 주소와 이름 입력 | Microsoft는 갱신에 24시간 이상 걸릴 수 있다고 안내한다. Outlook에서 직접 지정한 이름과 피드 이름 변경은 따로 확인한다. |

앱 버전과 언어에 따라 메뉴 이름이 다를 수 있다. 절차의 근거는
[Google URL 구독 안내](https://support.google.com/calendar/answer/37100?hl=en-uk),
[Apple 구독·새로고침 안내](https://support.apple.com/en-ie/guide/calendar/icl1024/mac),
[Microsoft 가져오기·구독 안내](https://support.microsoft.com/en-us/outlook/import-or-subscribe-to-a-calendar-in-outlook-com-or-outlook-on-the-web)다.
이 안내는 실제 CAL 호환성 테스트 통과 기록이 아니다.

BATON의 URL 발급 화면에는 다음 문구를 함께 표시하는 것을 권장한다. 화면 구현은 BATON 담당이다.

> 캘린더 앱에서 ‘URL로 구독’을 선택해 이 주소를 등록하세요. 일정 변경과 취소는 앱의 갱신 주기에
> 따라 반영됩니다. 주소는 다시 표시되지 않으니 등록을 마칠 때까지 이 화면을 유지하세요.
> 주소를 잃었거나 공유했다면 새 주소를 발급받고 기존 구독을 교체하세요.

## 테스트 준비

1. [운영 구성](operations.md)에 따라 `cal.b4ton.com`에 외부에서 접근 가능한 HTTPS를 준비한다.
   로컬 자체 서명 인증서는 Google·Outlook 외부 수집 테스트에 사용하지 않는다.
2. 실제 회원 데이터와 분리한 테스트 CAL 인스턴스·DB를 사용한다. 아래 예시 시즌 ID와 항목 ID가
   운영 데이터에 들어가지 않게 한다. BATON의 사용자 권한 결정을 생략한 운영 구독을 만들지 않는다.
3. `CAL_INTERNAL_BASE_URL`은 인증된 내부 연결 주소로, `BATON_CAL_INTERNAL_TOKEN`은 외부 비밀
   설정에서 주입한다. 관리 API를 공개 프록시에 추가하지 않는다.
4. 아래 요청은 저장소 루트에서 실행한다. 응답 코드와 `APPLIED`를 확인하고 다음 단계로 넘어간다.
   구독 URL·토큰은 보고서·스크린샷·공유 터미널 기록에 남기지 않는다.

```shell
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer $BATON_CAL_INTERNAL_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @contracts/examples/schedule-snapshot.zoned-active-r0.json \
  "$CAL_INTERNAL_BASE_URL/internal/api/v1/schedule-snapshots"

curl --fail-with-body --silent --show-error -X PUT \
  -H "Authorization: Bearer $BATON_CAL_INTERNAL_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @contracts/examples/season-calendar-metadata.r0.json \
  "$CAL_INTERNAL_BASE_URL/internal/api/v1/seasons/f5316f93-d49e-4230-b1d0-9e9c2d079819/calendar-metadata"
```

BATON의 테스트 발급 화면에서 이 시즌의 구독 주소를 발급받아 세 앱에 등록한다. BATON 연동 전에는
내부 ID 지정 생성 API로 테스트 구독을 발급할 수 있지만, 그 결과를 사용자 화면 검증으로 기록하지
않는다. 피드 경로는 수신한 일회성 응답을 사용하며 문서의 `...ics`를 실제 주소로 요청하지 않는다.

## 순차 확인 목록

각 단계의 **앱 반영을 확인한 뒤** 다음 스냅샷을 보낸다. 변경·취소·재활성화를 한꺼번에 보내면
앱이 중간 상태를 수집하지 않아 호환성을 판단할 수 없다. 과거 날짜 픽스처는 해당 날짜로 이동한다.

| 단계 | 입력·조작 | 서버 기대값 | 앱에서 기록할 내용 |
| --- | --- | --- | --- |
| 생성 | `schedule-snapshot.zoned-active-r0.json` | `UID` 고정, `SEQUENCE:0`, `CONFIRMED` | 초기 제목·일시·시간대·시즌 이름 |
| 시간 변경 | 같은 수신 경로에 `schedule-snapshot.zoned-active-r2.json` | 같은 UID, `SEQUENCE:2`, 변경된 시각 | 중복 일정 없이 시각 변경, 반영 지연 |
| 취소 | `schedule-snapshot.zoned-cancelled.json` | 같은 UID, `SEQUENCE:3`, `CANCELLED` | 삭제·취소 표시·잔존 중 실제 동작 |
| 재활성화 | `schedule-snapshot.zoned-reactivated.json` | 같은 UID, `SEQUENCE:4`, `CONFIRMED` | 같은 일정으로 복원되는지 |
| 이름 변경 | 이름 PUT에 `season-calendar-metadata.r2.json` | `X-WR-CALNAME` 변경, 일정 UID·SEQUENCE 유지 | 앱의 사용자 지정 이름과 피드 이름을 구분해 기록 |
| UTC·현지 시점 | `schedule-snapshot.utc-point-active.json`, `schedule-snapshot.zoned-point-active.json` | DTSTART만 있으며 DTEND 없음 | 앱이 임의 길이를 표시하는지, 시작 시각 |
| 종일 | `schedule-snapshot.all-day-active.json` | `VALUE=DATE`, 배타적 종료일 | 하루가 더 표시되거나 날짜가 이동하는지 |
| DST·자정 | 아래의 추가 시간대 픽스처를 수신 | `America/New_York` VTIMEZONE, 현지 시각 보존 | 봄·가을 전환 전후 표시와 자정 넘김 |
| 이스케이프 | 아래 DST 봄 픽스처의 한글·줄바꿈·쉼표·세미콜론 | UTF-8·TEXT 이스케이프 | 줄바꿈과 특수문자, 깨진 한글 여부 |
| 회전 | 테스트 구독 rotate 후 이전 주소 요청 | 이전 URL은 본문 없는 404 | 이전 캐시 잔존과 새 URL 재등록 결과 |
| 폐기 | 테스트 구독 DELETE | 현재 URL도 본문 없는 404 | 앱에서 수동 제거 후 테스트 종료 |

DST 확인에는 아래 예시를 기존 시간대 스냅샷에서 변환해 사용한다. 각 파일은 별도 항목·이벤트를
사용하고 원본 시간 계산을 CAL에 맡기지 않는다. `jq`가 표준 JSON 직렬화를 담당한다.

```shell
jq '.eventId="81000000-0000-0000-0000-000000000001"
  | .sourceItemId="82000000-0000-0000-0000-000000000001"
  | .summary="DST 봄 전환과 자정 확인"
  | .description="한글, 쉼표; 세미콜론\n줄바꿈과 이모지 😀"
  | .time.zoneId="America/New_York"
  | .time.startLocal="2027-03-13T23:30:00"
  | .time.endLocal="2027-03-14T03:30:00"' \
  contracts/examples/schedule-snapshot.zoned-active-r0.json \
  | curl --fail-with-body --silent --show-error \
    -H "Authorization: Bearer $BATON_CAL_INTERNAL_TOKEN" -H 'Content-Type: application/json' \
    --data-binary @- "$CAL_INTERNAL_BASE_URL/internal/api/v1/schedule-snapshots"

jq '.eventId="81000000-0000-0000-0000-000000000002"
  | .sourceItemId="82000000-0000-0000-0000-000000000002"
  | .summary="DST 가을 전환 확인"
  | .time.zoneId="America/New_York"
  | .time.startLocal="2027-11-07T00:30:00"
  | .time.endLocal="2027-11-07T02:30:00"' \
  contracts/examples/schedule-snapshot.zoned-active-r0.json \
  | curl --fail-with-body --silent --show-error \
    -H "Authorization: Bearer $BATON_CAL_INTERNAL_TOKEN" -H 'Content-Type: application/json' \
    --data-binary @- "$CAL_INTERNAL_BASE_URL/internal/api/v1/schedule-snapshots"
```

## 실제 앱 검증 기록

2026-09-05 현재 운영 서버·HTTPS·실제 앱 구독 환경이 없어 아래 항목은 **미실행**이다.
자동 HTTP·iCalendar 테스트와 로컬 HTTPS 프록시 검증은 별도 결과이며 앱 통과로 대체하지 않는다.

| 앱 | 앱·OS 버전 / 계정 시간대 | 서버 변경 시각 → 앱 관측 시각 / 지연 | 단계별 결과·취소 표시·이름 갱신 |
| --- | --- | --- | --- |
| Google Calendar 웹 | 미실행 | 미실행 | 미실행 |
| Apple Calendar Mac | 미실행 | 미실행 | 미실행 |
| Outlook 웹 | 미실행 | 미실행 | 미실행 |

외부 수집기가 변경을 가져오지 않았으면 실패로 단정하지 않고 확인 시각과 다음 확인 필요 여부를
기록한다. 전체 구독 URL·토큰·회원 이름은 기록하지 않는다. 외부 캘린더 알림을 켜지 않고 테스트해
가짜 일정이 개인 알림이나 다른 사람에게 전달되지 않게 한다.
