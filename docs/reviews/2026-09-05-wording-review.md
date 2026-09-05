# 사용자 화면과 안내 문구 검토

검토일: 2026-09-05. CAL 구독 화면·사용자 안내·README·제품 기준과 연결된 BATON의 계정·업무·인수인계 화면을 검토했다. BATON 화면의 문구를 검색한 뒤 아래 항목의 사용 위치와 동작을 확인했다. 모든 문장이 수정 대상이라는 뜻은 아니다.

아래 36개 항목을 화면·오류 안내·제품 문서에 반영했다. BATON 검증 브랜치는
`codex/wording-clarity-20260905`의 `3e263144`이며 원본 `codex/review-feature-readiness`에
같은 파일 내용의 커밋 `094e8758`로 반영했다. 표의 화면 문구는 검증 브랜치 기준이다.
원문에는 필요한 부분만 인용한 항목이 있다. N·M은 화면에 표시할 개수다.

원본에서 진행 중인 다른 문구 수정은 보존했다. 겹치는 항목의 원본 작업 폴더에는 다음 표현을
유지했으며 같은 화면 이름을 인용하는 안내와 테스트도 해당 표현을 따른다.

| 번호 | 원본 작업 폴더에 유지한 표현 |
| --- | --- |
| 12 | 회차 일정과 반복 업무 마감을 캘린더 앱에서 확인하세요. 일정은 BATON에서만 수정할 수 있습니다. |
| 16 | 로그인 방법 선택 |
| 18 | 같은 이메일이어도 로그인 방법이 다르면 별도 계정입니다. |
| 23 | 운영 점검 |
| 24 | 자동 점검에서 발견된 문제가 없습니다. |
| 28 | 생성 시점의 점검 결과 |

CAL 사용자 안내의 14·15번은 내용은 유지하고 기존 문서와 같은 ‘한다’ 문체로 맞췄다.

기준은 실무에서 쓰는 용어, 상태와 동작의 명확성, 불필요한 설명 제거다. 결과 미확인을 실패로 바꾸거나, 요청 중단을 이미 처리한 해제의 취소로 바꾸지 않는다.

## 캘린더 구독 화면과 안내

| 번호 | 변경 전 문구 | 검증 브랜치 반영 문구 | 이유·적용 조건 | 위치 |
| --- | --- | --- | --- | --- |
| 1 | 미생성 | 구독 없음 | 내부 생성 상태 대신 사용자에게 보이는 상태를 쓴다. | [CalendarSubscriptionList.tsx:12](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/calendar/CalendarSubscriptionList.tsx:12) |
| 2 | 선택 지우기 | 선택 초기화 | 구독 삭제와 체크 표시 초기화를 구분한다. | [CalendarSubscriptionList.tsx:80](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/calendar/CalendarSubscriptionList.tsx:80) |
| 3 | 결과 확인 필요 | 해제 여부 확인 필요 | 확인할 대상을 명시한다. 해제 실패로 단정하지 않는다. | [CalendarBulkRevocation.tsx:13](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/calendar/CalendarBulkRevocation.tsx:13) |
| 4 | 계정 확인 필요 | 로그인 계정 확인 필요 | 어떤 계정을 확인해야 하는지 명시한다. | [CalendarBulkRevocation.tsx:14](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/calendar/CalendarBulkRevocation.tsx:14) |
| 5 | 구독이 바뀌어 확인 필요 | 구독 변경됨 · 다시 확인 | 변경 사실과 다음 행동을 구분한다. | [CalendarBulkRevocation.tsx:13](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/calendar/CalendarBulkRevocation.tsx:13) |
| 6 | 선택한 구독 해제 확인 | N개 구독 해제 | 확인 버튼이 실제 해제를 실행한다는 점을 드러낸다. N은 선택한 개수다. | [CalendarBulkRevocation.tsx:104](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/calendar/CalendarBulkRevocation.tsx:104) |
| 7 | 남은 요청 중단 | 해제 작업 중단 | 내부 요청 대신 사용자가 수행 중인 작업 이름을 쓴다. 이미 보낸 요청은 완료될 수 있다는 안내는 유지한다. | [CalendarBulkRevocation.tsx:109](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/calendar/CalendarBulkRevocation.tsx:109) |
| 8 | {total}개 중 {results.length}개 확인했습니다. 선택한 구독을 해제하고 있습니다. | 구독 해제 중 · 결과 확인 M/N개 | 두 문장을 줄인다. M은 결과가 나온 항목 수이며 해제 성공 수와 구분한다. | [CalendarBulkRevocation.tsx:108](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/calendar/CalendarBulkRevocation.tsx:108) |
| 9 | 처리 중이거나 확인이 필요한 항목은 ‘선택 마치기’를 누른 뒤 항목을 펼쳐 상태를 확인해 주세요. 이미 보낸 요청은 중단해도 처리될 수 있습니다. | ‘선택 마치기’ 후 처리 중·미확인 항목을 열어 확인하세요. 이미 요청한 해제는 중단 후에도 완료될 수 있습니다. | 재확인 절차와 중단의 한계를 유지하면서 줄인다. | [CalendarBulkRevocation.tsx:117](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/calendar/CalendarBulkRevocation.tsx:117) |
| 10 | 기존 주소를 끄고 재발급 | 새 주소 발급 | 주소를 끈다는 표현을 없앤다. 기존 주소가 무효가 된다는 확인 설명은 유지한다. | [CalendarSubscriptionPanel.tsx:159](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/calendar/CalendarSubscriptionPanel.tsx:159) |
| 11 | 주소가 표시되지 않으면 상태를 확인한 뒤 필요한 작업을 선택해 주세요. | 공통 문장 삭제. 조회 실패에는 ‘상태 다시 확인’을 눌러 주세요. | 필요한 작업이 무엇인지 알 수 없다. 로그인 오류에는 로그인 안내를 쓰는 등 오류별로 안내한다. | [CalendarSubscriptionPanel.tsx:150](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/calendar/CalendarSubscriptionPanel.tsx:150) |
| 12 | BATON의 회차 일정과 마감이 있는 루틴을 읽기 전용으로 구독합니다. 일정 수정은 BATON에서 해 주세요. | 회차 일정과 루틴 마감을 캘린더 앱에서 확인하세요. 일정은 BATON에서만 수정할 수 있습니다. | 사용자가 할 수 있는 일을 설명하고 수정 제한을 유지한다. | [CalendarSubscriptionPanel.tsx:141](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/calendar/CalendarSubscriptionPanel.tsx:141) |
| 13 | 구독 요청 결과를 확인하지 못했습니다. 상태를 먼저 다시 조회해 주세요. | 구독 처리 결과를 확인하지 못했습니다. 상태를 다시 확인해 주세요. | 조회·먼저·다시의 중복을 줄이고 화면 용어와 맞춘다. | [CalendarSubscriptionException.java:14](/Users/lim/devProject/personal/manager-cal-subscription/application/src/main/java/com/personal/baton/application/calendar/CalendarSubscriptionException.java:14) |
| 14 | 표시한 시각은 BATON 구독 상태를 확인한 시간이며 Google·Apple·Outlook이 일정을 가져간 시간이 아니다. | 표시 시각은 BATON에서 구독 상태를 확인한 시간입니다. 캘린더 앱의 갱신 시간은 아닙니다. | 앱 이름 나열을 줄이고 구독 상태 확인과 앱 갱신을 구분한다. | [calendar-subscription-guide.md:29](/Users/lim/devProject/personal/baton-cal/docs/calendar-subscription-guide.md:29) |
| 15 | 권한 회수와 활동 중지는 자동 폐기를 요청하며, CAL에 전달되기 전에는 기존 주소가 작동할 수 있다. | 팀 권한이 회수되거나 구성원 활동이 중지되면 구독 해제를 자동 요청합니다. 해제 전까지 기존 주소를 사용할 수 있습니다. | 폐기·전달 같은 내부 용어를 구독 해제의 동작과 시점으로 바꾼다. | [calendar-subscription-guide.md:15](/Users/lim/devProject/personal/baton-cal/docs/calendar-subscription-guide.md:15) |

## BATON 공통 화면

| 번호 | 변경 전 문구 | 검증 브랜치 반영 문구 | 이유·적용 조건 | 위치 |
| --- | --- | --- | --- | --- |
| 16 | 로그인 방법은 세 가지, 계정 경계는 또렷하게. | BATON 계정 | 계정 경계라는 추상적인 홍보 문구를 줄인다. 가입·계정 관리에도 쓰이는 공통 화면이다. | [AuthPageShell.tsx:26](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/auth/AuthPageShell.tsx:26) |
| 17 | 사용할 로그인 수단을 선택하세요. 공급자 간 계정 연결은 안전한 재인증 흐름을 마련한 뒤 제공합니다. | 로그인 방법을 선택하세요. | 개발 계획을 로그인 안내에서 뺀다. 로그인 방법별 계정 구분 안내는 별도로 유지한다. | [LoginPage.tsx:11](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/pages/LoginPage.tsx:11) |
| 18 | 같은 이메일이어도 공급자가 다르면 안전을 위해 자동으로 계정을 합치지 않습니다. | 이메일이 같아도 로그인 방법이 다르면 별도 계정입니다. | 공급자와 계정 병합 대신 사용자가 알아야 할 결과를 쓴다. | [AuthPageShell.tsx:29](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/auth/AuthPageShell.tsx:29) |
| 19 | 연결한 구성원은 다른 계정이 다시 claim할 수 없습니다. 실제 본인인지 확인한 뒤 진행하세요. | 이미 연결된 구성원은 다른 계정에 연결할 수 없습니다. 본인 이름인지 확인하세요. | claim을 화면의 기존 연결 용어로 통일한다. | [AccountMembershipPanel.tsx:149](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/membership/AccountMembershipPanel.tsx:149) |
| 20 | 로그인 상태를 확인하지 못했습니다. 연결 상태를 추측하지 않고 다시 확인해 주세요. | 로그인 상태를 확인하지 못했습니다. 다시 확인해 주세요. | 사용자가 수행할 수 없는 추측 금지 안내를 뺀다. | [AccountMembershipPanel.tsx:64](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/membership/AccountMembershipPanel.tsx:64) |
| 21 | 현재 브라우저를 포함해 이 계정으로 로그인한 모든 기존 세션을 종료합니다. | 현재 기기를 포함해 모든 기기에서 로그아웃합니다. | 세션 종료를 버튼과 같은 로그아웃 용어로 맞춘다. | [AccountSecurityPanel.tsx:184](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/auth/AccountSecurityPanel.tsx:184) |
| 22 | 팀의 책임 지도 | 역할과 담당자 | 비유 대신 화면에 나오는 정보를 제목으로 쓴다. | [WorkspaceRolesView.tsx:43](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/workspace/WorkspaceRolesView.tsx:43) |
| 23 | 조직 연속성 레이더 | 확인이 필요한 업무 | 무엇을 보여주는 목록인지 설명한다. 위의 유사한 보조 제목은 함께 줄인다. | [WorkspaceTodayView.tsx:194](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/workspace/WorkspaceTodayView.tsx:194) |
| 24 | 현재 규칙에서 먼저 살필 연속성 공백을 찾지 못했어요. | 자동 점검에서 확인된 주의 항목이 없습니다. | 자동 점검 결과라는 범위를 유지한다. 모든 업무에 문제가 없다고 단정하지 않는다. | [WorkspaceTodayView.tsx:232](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/workspace/WorkspaceTodayView.tsx:232) |
| 25 | 조직의 기억 탐색 | 기록 검색 | 화면의 실제 기능 이름을 쓴다. | [RecordSearchView.tsx:120](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/workspace/records/RecordSearchView.tsx:120) |
| 26 | 바통북 미리보기 | 인수인계 문서 미리보기 | 바통북의 용도를 처음부터 알 수 있게 한다. 관련 화면·안내의 명칭도 함께 맞췄다. | [WorkspaceHandoffView.tsx:133](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/workspace/WorkspaceHandoffView.tsx:133) |
| 27 | 결정과 이유를 팀의 기억에 남겼어요. | 결정과 이유를 저장했습니다. | 완료한 작업만 짧게 알린다. | [workspaceContentActions.ts:404](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/workspace/workspaceContentActions.ts:404) |
| 28 | BRIEF · 생성 당시의 운영 신호 | 생성 시점의 점검 항목 | 서비스 구성과 신호라는 내부 용어를 화면 내용으로 바꾼다. | [BriefEditionPanel.tsx:53](/Users/lim/devProject/personal/manager-cal-subscription/frontend/src/features/brief/BriefEditionPanel.tsx:53) |

## CAL 소개와 제품 문서

| 번호 | 변경 전 문구 | 검증 브랜치 반영 문구 | 이유·적용 조건 | 위치 |
| --- | --- | --- | --- | --- |
| 29 | 가져오기 전용 iCalendar 투영 MVP | 읽기 전용 캘린더 구독 MVP | 파일 가져오기와 URL 구독을 혼동하지 않게 한다. | [spec.md:4](/Users/lim/devProject/personal/baton-cal/docs/PRD/0001_product-baseline/spec.md:4) |
| 30 | BATON CAL은 BATON이 확정한 시즌 일정, 운영 회차와 마감을 읽기 전용 iCalendar 피드로 투영하는 독립 서비스다. | BATON CAL은 확정된 시즌 일정·회차·마감을 읽기 전용 캘린더 구독(.ics)으로 제공하는 독립 서비스다. | 소개에서는 투영 대신 제공하는 기능을 설명한다. | [README.md:3](/Users/lim/devProject/personal/baton-cal/README.md:3) |
| 31 | 원본 개정 번호가 전진할 때만 `SEQUENCE`가 전진한다. | 원본 개정 번호가 증가할 때만 `SEQUENCE`를 증가시킨다. | 숫자의 변화를 증가로 표현한다. | [spec.md:32](/Users/lim/devProject/personal/baton-cal/docs/PRD/0001_product-baseline/spec.md:32) |
| 32 | 사용자는 BATON에서 구독을 폐기하거나 회전한다. | 사용자는 BATON에서 구독을 해제하거나 주소를 재발급한다. | 사용자 절차는 실제 화면의 해제·발급 용어로 쓴다. | [spec.md:45](/Users/lim/devProject/personal/baton-cal/docs/PRD/0001_product-baseline/spec.md:45) |
| 33 | CAL이 소유한다. | CAL이 담당한다. | 기능 분담을 설명하는 제목에는 담당이 더 직접적이다. 데이터 소유권을 정의하는 ADR의 용어는 유지한다. | [README.md:11](/Users/lim/devProject/personal/baton-cal/README.md:11) |
| 34 | 피드 조회의 최소 운영 관측 | 구독 요청·오류 모니터링 | 관측 대상을 드러내고 기준 없는 최소를 뺀다. | [README.md:18](/Users/lim/devProject/personal/baton-cal/README.md:18) |
| 35 | BATON이 권한과 범위를 판단하고 CAL에 불투명한 구독 생성 명령을 전달한다. | BATON이 구독 권한과 범위를 확인한 뒤 CAL에 구독 생성을 요청한다. | 불투명한 명령은 의미가 불분명하다. 권한 확인과 요청 순서를 유지한다. | [spec.md:41](/Users/lim/devProject/personal/baton-cal/docs/PRD/0001_product-baseline/spec.md:41) |
| 36 | 이 문서는 경로의 관측 계약을 정의한다. 작업 트리의 실행 골격 존재 여부와 별개로 실제 배포, BATON 연동과 운영 준비는 검증 결과 없이 완료된 것으로 간주하지 않는다. | 이 문서는 API 동작을 정의한다. 배포·BATON 연동·운영 준비는 각각 검증한 뒤 완료로 기록한다. | 관측 계약·실행 골격 같은 추상적인 표현을 줄이고 완료 기준을 남긴다. | [spec.md:14](/Users/lim/devProject/personal/baton-cal/docs/PRD/0002_mvp-contract/spec.md:14) |

## 용어 통일과 유지할 문구

- 체크 표시를 지우는 동작은 **선택 초기화**, 캘린더 연결을 끊는 동작은 **구독 해제**로 구분한다.
- 사용자 화면에서는 **구독 해제**, **주소 재발급**, **로그아웃**, **캘린더 앱**으로 통일한다. 기술 문서의 토큰 폐기·회전, 세션, 클라이언트까지 일괄 치환하지 않는다.
- **팀·시즌 검색**, **해제된 구독 숨기기**, **구독 더 보기**, **구독 주소 복사**, **상태 다시 확인**은 이미 구체적이므로 유지한다.
- `UID`, `SEQUENCE`, `ETag`, `멱등성`, `트랜잭션` 등 계약·구현에 필요한 용어는 유지한다. 제품 소개나 사용자 안내에서 처음 쓰면 짧게 뜻을 설명한다.
- 로그인 정보·저장된 일정·진행 중 해제에 미치는 영향은 짧게 고치더라도 생략하지 않는다.
- 같은 버튼명을 인용한 도움말과 화면 테스트를 함께 맞췄다. 검증 브랜치에서 구독 화면 72건,
  로그인·구성원 연결·역할·기록·인수인계 화면 34건, 서버의 점검 안내·구독 API 테스트 11건이
  통과했다. 모바일 로그인 전용 테스트의 PC·WebKit 실행 2건은 기존 설정대로 제외됐다.
  프런트 타입 검사와 프로덕션 빌드도 통과했다. 새 테스트나 검증용 제품 코드는 추가하지 않았다.
- 원본의 다른 수정과 통합한 뒤 구독 화면 72건과 타입 검사를 포함한 프로덕션 빌드를 다시
  통과했다. CAL은 문서만 변경했으므로 서버 테스트를 다시 실행하지 않았다.
