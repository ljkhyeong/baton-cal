# 추가 요금 없는 외부 API 연동

- 확인일: 2026-09-07
- 기준: CAL `30f88e7`, 외부 서비스 이용료를 추가하지 않는다는 사용자 요청
- 선택: 기존 `.ics` 구독의 앱별 등록 편의 개선. 제공자 API 직접 연동은 보류

## 적용 기준

- 기본 캘린더 제공은 기존 `.ics` 구독을 유지한다. 외부 API 호출료가 없으며 서버·도메인은 기존 운영 범위다.
- Nylas·Cronofy·AddEvent 같은 중계 서비스를 새로 도입하지 않는다.
- 무료 체험 종료 후 결제, 유료 구독, 초과 사용 과금을 전제로 하는 연동은 제외한다.
- 외부 API를 활성화하기 전에 해당 계정의 무료 범위와 요청 제한을 확인한다.
  무료 범위를 넘으면 동기화를 보류하고, 비용이 발생하는 한도 증액이나 결제 설정을 자동 적용하지 않는다.

## 후보별 비용과 역할

| 후보 | 확인한 비용 조건 | 적용 위치와 남는 작업 |
| --- | --- | --- |
| 한국천문연구원 특일 정보 | 포털에 무료로 명시. 활용 신청·인증키·호출 제한 필요 | BATON에서 공휴일 데이터를 저장·갱신한다. 공휴일 표시나 회차 제외 규칙은 BATON이 결정하며 CAL은 확정 일정만 받는다. |
| Google Calendar API | 일반 사용은 추가 요금 없음. 공식 문서는 2026년 중 초과 사용 과금 도입 계획과 일일 무료 기준을 안내하므로 무제한 무료로 취급하지 않는다. | 사용자 동의 후 일정 생성·수정·삭제를 단방향으로 전달한다. 계정 연결, 일정 ID 연결, 재시도와 호출량 제한이 필요하다. |
| Microsoft Graph Calendar API | 표준 API는 이용 자격과 사용 한도 내에서 추가 API 요금 없이 제공된다. 계정·라이선스 조건은 별도다. | 기존 사용 가능한 Outlook 계정의 권한 범위에서 연동한다. 새 유료 Microsoft 365 구독을 구매하는 방식은 제외한다. |

Google 공식 문서의 일일 기준은 프로젝트당 1,000,000회다. 실제 적용 한도는 프로젝트 생성 시기와
설정에 따라 확인해야 한다. 서비스 자체 호출량과 재시도를 제한하고, 제공자 측의 무료 한도 차단 조건을
확인한 뒤 활성화한다. 이 문서는 한도 차단 구현이나 비용 보장을 완료했다는 뜻이 아니다.

## 이번 적용 범위

추가 요금과 연동 코드를 늘리지 않도록 기존 구독을 활용한다. BATON에서 주소 발급 후 등록 안내를
자동으로 펼치고 Google·Apple·Outlook 개인/회사·학교 계정 중 선택한 앱의 절차만 표시한다.
Google의 공식 URL 등록 화면과 앱별 공식 페이지를 새 탭으로 연다. 구독 주소는 링크에 넣지 않으며,
주소 복사 실패 시 직접 선택해 복사할 수 있다. 앱별 갱신 주기는 유지되며 실시간 동기화를 뜻하지 않는다.

공휴일 활용은 BATON의 별도 기능이다. 제공자 API 직접 연동은 빠른 갱신이 실제 요구사항으로 확정될 때
아래 조건과 비용을 다시 확인한다.

- 직접 연동: BATON에서 변경이 확정된 뒤 외부 캘린더로 전달한다. 외부 캘린더의 수정으로 BATON 원본을
  변경하지 않는다. 기존 `.ics` 주소 구독과 API를 통한 일정 복사 사이의 중복 등록도 처리해야 한다.
- 계정 연결: 현재 BATON의 `DiscardingOAuth2AuthorizedClientRepository`는 로그인 토큰을 저장하지 않는다.
  로그인 설정을 그대로 캘린더 권한으로 사용하지 않고, 별도 동의·연결 해제·자격 증명 보관을 설계한다.
- 공휴일 활용: 필요한 연도의 데이터를 저장하고 정기 갱신한다. 화면을 열 때마다 외부 API를 호출하지 않는다.
  데이터 변경만으로 이미 확정된 일정을 자동 수정하지 않는다.

## 공식 근거

- [한국천문연구원 특일 정보: 비용·활용 신청·요청 제한](https://www.data.go.kr/data/15012690/openapi.do)
- [Google Calendar API: 무료 범위·초과 사용 과금 계획·요청 제한](https://developers.google.com/workspace/calendar/api/guides/quota)
- [Microsoft Graph: 표준 API와 과금 API 구분](https://learn.microsoft.com/en-us/graph/metered-api-overview)
- [Microsoft Graph: 일정 생성과 계정별 권한](https://learn.microsoft.com/en-us/graph/api/calendar-post-events?view=graph-rest-1.0)
- [Google Calendar: URL 등록 화면과 절차](https://support.google.com/calendar/answer/37100?hl=en)
- [Apple Calendar: 구독 등록](https://support.apple.com/en-ie/guide/iphone/iph3d1110d4/ios)
- [Outlook: 계정별 URL 구독 절차](https://support.microsoft.com/en-us/outlook/import-or-subscribe-to-a-calendar-in-outlook-com-or-outlook-on-the-web)
