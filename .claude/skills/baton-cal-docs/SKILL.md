---
name: baton-cal-docs
description: BATON CAL의 README, PRD, ADR, HANDOFF, 운영·연동 안내, 검토 문서를 작성하거나 갱신할 때 사용한다. 문서 위치, 문체, 구현 상태 표현, 검증 기록 형식과 링크 검사를 정한다.
---

# BATON CAL 문서 작성

## 문서 위치

| 내용 | 위치 |
| --- | --- |
| 제품 동작·HTTP 계약 | `docs/PRD/`. 계약 의미는 PRD-0002 |
| 구조·기술 결정 | `docs/ADR/<4자리 번호>_<주제>/adr.md`. 제목 `ADR-<번호>: <결정>`, 상태·결정일, `결정` 절을 두고 필요하면 배경·결과·범위·보류한 대안을 쓴다 |
| 현재 상태·최신 검증·남은 작업·제한 | `HANDOFF.md` |
| 실행·배포·복원 | `README.md`, `docs/operations.md`, `docs/integration-runtime.md` |
| 개발 검증 절차 | `docs/development.md` |
| 계약 사용법·버전·골든 | `contracts/README.md` |
| 릴리스 노트·게시 기록 | `docs/releases/contracts-v{버전}.md`, `docs/contract-release-history.md` |
| 기능·API·문구 검토 결과 | `docs/reviews/<YYYY-MM-DD>-<주제>.md` |
| 캘린더 앱 사용자 안내 | `docs/calendar-subscription-guide.md` |

같은 내용은 정식 문서 한 곳에 두고 다른 문서에서는 링크한다. 게시된 계약 입력(`contracts/**`, 루트 `LICENSE`,
PRD-0002)을 바꾸려면 다음 계약 버전이 필요하므로 baton-cal-contract-release를 따른다.

## 문체

- 한글 `한다` 문체로 짧게 쓴다. 사용자 안내 화면에 들어갈 문장은 해당 문서의 기존 문체를 따른다.
- 내부 구현 용어 대신 실무 용어와 사용자가 보는 상태·동작을 쓴다. 비유·홍보 문구와 개발 계획은 넣지 않는다.
- 결과를 바꿔 쓰지 않는다. 결과 미확인을 실패로, 요청 중단을 이미 처리한 작업의 취소로 쓰지 않는다.
  자동 점검 결과를 전체 정상으로 단정하지 않는다.
- API 필드·코드 식별자·경로·표준명은 원문 그대로 백틱으로 감싼다.
- 외부 표준·도구의 근거는 고정 버전 URL로 연결한다. 예: Alertmanager `v0.32.1` 태그의 소스.
- 주변 문서의 줄바꿈 폭과 표 형식을 따른다.

## 구현 상태 표현

- 구현·검증·배포하지 않은 내용을 완료로 쓰지 않는다. 로컬 검증, CI, 공식 릴리스, BATON 채택, 운영 배포,
  실제 캘린더 앱 검증을 구분한다.
- 검증 기록은 `기준 커밋·미커밋 여부 / 명령·범위 / 환경 / 실행·캐시 재사용 / 결과·제한` 순서로 쓰고 로그 경로를 붙인다.
  재사용한 결과는 재사용 근거를 함께 쓴다.
- `HANDOFF.md`에는 최신 관련 검증과 남은 작업만 둔다. 대체된 과거 기록은 지우고 Git 이력에 맡긴다.
  새 제한이 생기거나 해소되면 `현재 제한`을 함께 고친다.

## 검증

```bash
git diff --check
python3 -B .claude/skills/baton-cal-docs/scripts/check_links.py <바꾼 .md 파일>
```

링크 검사는 저장소 안의 상대 링크와 GitHub 제목 앵커를 확인한다. 외부 URL과 다른 저장소의 절대 경로는
검사하지 않으므로 바꾼 경우 직접 확인한다. 인자를 생략하면 Git이 추적하는 모든 Markdown을 검사한다.
검사기를 고치면 `python3 -B -m unittest discover -s .claude/skills/baton-cal-docs/scripts`로 회귀 테스트를 실행한다.
문서만 바꿨다면 동작 테스트는 실행하지 않고 기존 결과를 재사용한다고 기록한다.
