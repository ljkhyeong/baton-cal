---
name: baton-cal-contract-release
description: BATON CAL 계약 버전 결정, 릴리스 노트 준비, contracts-v 태그와 GitHub 불변 릴리스 게시, 게시 기록 갱신에 사용한다. 태그 푸시와 릴리스 게시는 사용자가 해당 버전 게시를 명시적으로 요청한 경우에만 진행한다.
---

# BATON CAL 계약 릴리스

정식 명령은 `docs/contract-release-procedure.md`에 있다. 이 스킬은 판단 기준과 확인 지점만 다루며 명령은 절차 문서를
그대로 따른다. 태그 푸시와 초안 해제는 되돌릴 수 없으므로 각 단계 전에 대상 버전·태그·커밋·자산을 사용자에게 보이고 승인받는다.

## 현재 상태

```bash
cat contracts/VERSION
git tag -l 'contracts-v*'
gh release list --limit 10
```

게시 내역과 SHA-256은 `docs/contract-release-history.md`, 버전별 노트는 `docs/releases/`에서 확인한다.

## 버전 결정

계약 ZIP에는 `contracts/**`, 루트 `LICENSE`, PRD-0002가 들어간다. 이미 게시한 버전의 입력을 바꾸면 먼저
`contracts/VERSION`을 올린다.

| 상황 | 처리 |
| --- | --- |
| 새 필드·열거형·의미 변경 | 새 스키마 버전과 송신·수신 계약 테스트 데이터를 만들고 다음 버전의 RC로 게시 |
| BATON 연동 검증 전 | `-rc.N` 사전 릴리스로 게시 |
| RC가 계약 수정 없이 BATON 연동을 통과 | 안정 버전으로 바꿔 새 태그와 ZIP 게시 |
| RC에 계약 수정이 필요 | 다음 RC |
| 과거 안정 버전의 의미를 유지한 재포장 | 과거 태그에서 갈라진 `release/contracts-v*` 호환 브랜치. main에 병합하지 않는다 |

## 게시 전 확인

- 변경과 `docs/releases/contracts-v{버전}.md`가 PR로 main에 반영됐고, 노트가 게시할 커밋의 변경·검증과 맞다.
- 로컬과 원격 커밋이 같고 `git status --short` 출력이 비어 있다.
- 절차 문서의 검증 명령이 통과했고, 호환 브랜치라면 PR의 `계약 릴리스 HEAD 검증` 작업도 통과했다.
- 저장소의 릴리스 불변성 설정이 켜져 있다.

## 게시 후

- `gh release verify`와 `gh release verify-asset`로 릴리스와 자산 증명을 검증한다.
- 태그 커밋과 ZIP SHA-256을 `docs/contract-release-history.md`에 기록하고 `HANDOFF.md`를 갱신한다.
- BATON 채택은 별도 작업이다. BATON이 버전·태그·파일명·SHA-256을 고정하고 연동 테스트를 통과해야 채택으로 기록한다.

## 금지

- 게시한 태그를 삭제·이동하거나 자산을 교체하지 않는다. 수정이 필요하면 다음 버전을 만든다.
- 강제 푸시, 초안 검토 없는 바로 게시, CI 산출물을 공식 릴리스로 보고하는 일을 하지 않는다.
