# 추가 문구 검토

검토일: 2026-09-05.

BATON `5e145f50` 이후 작업 폴더에서 남은 문구 11개를 확인했다. 앞선 36개 항목을 다시 나열하지 않고, 검토 중 다른 작업에서 수정된 로그인·세션 안내와 조사 오류도 제외했다. 이 문서는 수정 제안이며 제품 코드는 변경하지 않았다.

1·2번은 사용자가 기능의 동작을 오해할 수 있어 먼저 수정하는 편이 좋다. 나머지는 중복 제목과 모호한 동작명이다. 원문은 필요한 부분만 인용했으며 N은 표시할 개수다.

| 번호 | 현재 문구 | 수정안 | 이유·유지할 의미 | 위치 |
| --- | --- | --- | --- | --- |
| 1 | 개인 캘린더 구독은 해제를 요청하며, 완료될 때까지 기존 일정이 보일 수 있습니다. | 개인 캘린더 구독 해제를 요청합니다. 해제 전까지 기존 주소를 사용할 수 있습니다. 앱에 저장된 일정은 직접 삭제해 주세요. | 해제 완료 뒤 앱에 저장된 일정까지 사라진다고 오해할 수 있다. | [AccountSecurityPanel.tsx:213](/Users/lim/devProject/personal/manager/frontend/src/features/auth/AccountSecurityPanel.tsx:213) |
| 2 | 현재 탭에 본문 초안을 24시간 보관합니다. 페이지를 다시 열고 같은 작성 창에서 불러올 수 있습니다. | 본문 초안은 현재 탭에서 최대 24시간 보관합니다. 같은 탭을 새로고침한 뒤 작성 창에서 다시 불러올 수 있습니다. | 다른 탭이나 기기에서도 복원되는 것처럼 안내하지 않는다. 작성자·역할 선택을 다시 확인하라는 기존 안내는 유지한다. | [RecordDraft.tsx:99](/Users/lim/devProject/personal/manager/frontend/src/features/workspace/RecordDraft.tsx:99) |
| 3 | 역할과 담당자 — 보조 제목과 큰 제목에 반복 | 큰 제목의 ‘역할과 담당자’만 유지 | 보조 제목과 큰 제목이 같다. 이 화면에서는 보조 제목을 생략한다. | [WorkspaceRolesView.tsx:43](/Users/lim/devProject/personal/manager/frontend/src/features/workspace/WorkspaceRolesView.tsx:43) |
| 4 | 구독 해제 확인 | 구독 해제 | 개별 구독의 최종 버튼은 실제 해제를 실행한다. 확인 영역의 접근성 이름과 영향 설명은 유지한다. | [CalendarSubscriptionPanel.tsx:159](/Users/lim/devProject/personal/manager/frontend/src/features/calendar/CalendarSubscriptionPanel.tsx:159) |
| 5 | 반복 업무를 운영 흐름에 추가했어요. | 반복 업무를 추가했습니다. | 추가한 대상을 직접 알린다. | [workspaceContentActions.ts:291](/Users/lim/devProject/personal/manager/frontend/src/features/workspace/workspaceContentActions.ts:291) |
| 6 | 반복 업무를 다시 운영 흐름에 꺼냈어요. 새 회차부터 포함됩니다. | 반복 업무를 복원했습니다. 새 회차부터 포함됩니다. | 복원이라는 실제 동작과 적용 시점을 함께 알린다. | [workspaceContentActions.ts:322](/Users/lim/devProject/personal/manager/frontend/src/features/workspace/workspaceContentActions.ts:322) |
| 7 | 회차를 다시 운영 화면에 꺼냈어요. | 회차를 복원했습니다. | 보관한 회차를 복원한 결과임을 알린다. | [workspaceContentActions.ts:375](/Users/lim/devProject/personal/manager/frontend/src/features/workspace/workspaceContentActions.ts:375) |
| 8 | 남은 정리 N건 / 정리 완료 / 정리 필요 | 미완료 항목 N개 / 완료 / 미완료 | 인수인계 체크리스트의 완료 상태다. 문서 정리 작업과 구분한다. 0개일 때는 ‘미완료 항목이 없습니다.’로 표시한다. | [WorkspaceRoleHandoffModals.tsx:398](/Users/lim/devProject/personal/manager/frontend/src/features/workspace/WorkspaceRoleHandoffModals.tsx:398) |
| 9 | 사람이 자료의 접근과 내용을 확인한 기록입니다. | 자료가 열리는지와 내용을 직접 확인한 기록입니다. | 자료의 접근이라는 어색한 표현을 사용자가 확인하는 행동으로 바꾼다. | [ResourceVerificationPanel.tsx:43](/Users/lim/devProject/personal/manager/frontend/src/features/resource-verification/ResourceVerificationPanel.tsx:43) |
| 10 | 이전 자료 확인 | 변경 전 확인 기록 | 다른 자료를 확인했다는 뜻이 아니라 자료가 변경되기 전 남긴 기록임을 명시한다. | [ResourceVerificationPanel.tsx:51](/Users/lim/devProject/personal/manager/frontend/src/features/resource-verification/ResourceVerificationPanel.tsx:51) |
| 11 | 충돌 전 입력 내용 / 이 초안은 저장하거나 다시 제출할 수 없습니다. | 저장하지 못한 입력 내용 / 최신 기록을 확인한 뒤 필요한 내용을 복사해 다시 편집하세요. 이 화면에서는 수정·재제출할 수 없습니다. | 충돌이라는 표현을 줄이고, 복사한 내용을 다시 저장하는 것까지 불가능하다고 오해하지 않게 한다. 읽기 전용 표시와 초안이 사라지는 조건은 유지한다. | [WorkspaceConflictDraft.tsx:51](/Users/lim/devProject/personal/manager/frontend/src/features/workspace/WorkspaceConflictDraft.tsx:51) |

## 확인 범위

- 구독 해제 안내는 [CAL 사용자 안내](../calendar-subscription-guide.md)의 기존 주소 사용 중단과 앱에 저장된 일정 삭제 절차에 대조했다.
- 초안은 `RecordDraft.tsx`의 `sessionStorage` 사용과 24시간 제한을 확인했다. 인수인계의 남은 개수는 `!item.completed`로 계산하는 체크리스트 수다.
- 로그인 응답 디코더의 내부 오류는 공용 요청 처리에서 사용자용 오류로 바뀐다. 해당 내부 문구는 수정 후보에서 제외했다.
- 화면을 직접 실행한 검토는 아니다. 현재 소스의 표시 조건과 호출 경로를 확인했으며, 제품 코드를 바꾸지 않아 실행 테스트는 하지 않았다.
