---
name: baton-cal-ci-fix
description: BATON CAL의 GitHub Actions `지속적 검증` 실패를 PR이나 main 실행에서 조사하고 고칠 때 사용한다. 실패 단계를 로컬 명령으로 재현하고 제품·테스트·환경 원인을 구분한다.
---

# BATON CAL CI 실패 대응

워크플로는 `.github/workflows/ci.yml` 하나다. 원격 상태를 바꾸는 재실행·푸시는 사용자 요청이나 승인 후에 한다.

## 실패 실행 찾기

```bash
gh auth status
gh pr checks <PR 번호>
gh run list --workflow ci.yml --branch main --limit 5
gh run view <실행 ID> --json headSha,event,conclusion,jobs
gh run view <실행 ID> --log-failed > /private/tmp/baton-cal-ci-<주제>.log
```

인증이 없으면 사용자에게 `gh auth login`을 요청한다. 로그 원문은 파일에 두고 응답에는 실패 단계와 핵심 부분만 옮긴다.
테스트 보고서는 CI 산출물로 올라가지 않으므로 실패 테스트는 로그에서 찾고 로컬 `build/test-results/test/`로 재현한다.

## 단계별 로컬 재현

| 작업 | 실패 단계 | 로컬 명령 |
| --- | --- | --- |
| 계약 릴리스 HEAD 검증 | 릴리스 기준 계약 팩 검증 | 해당 HEAD에서 `./gradlew --no-daemon verifyContractsZip` |
| 테스트와 OCI 이미지 검증, 게시 | 전체 테스트, 계약 팩 검증과 OCI 이미지 빌드 | 실패 테스트만 `./gradlew --no-daemon test --tests '<클래스>'`, 이후 `check bootBuildImage --imageName=baton-cal:ci-local` |
| 같음 | OCI 이미지 스모크 검증 | `./scripts/smoke-oci-image.sh baton-cal:ci-local` |
| 같음 | HTTPS 프록시와 운영 알림 스모크 검증 | `./scripts/smoke-operations.sh baton-cal:ci-local`, `bash scripts/smoke-alert-channels.sh` |
| 게시 | GHCR 로그인·이미지 게시 | 로컬에서 재현하지 않는다. 권한·레지스트리 상태를 보고한다 |

PR 실행은 PR 브랜치와 기준 브랜치의 합성 merge commit을, main 실행은 푸시된 커밋을 검증한다.
PR 실패는 `headSha`에 당시 기준 브랜치를 병합한 상태에서 재현한다. 기준 브랜치가 앞서 있지 않으면 `headSha`와 같다.
Docker를 쓰는 검증은 같은 장비의 다른 실행과 겹치지 않게 `docker ps`로 확인하고, 다른 작업의 컨테이너는 종료하지 않는다.

## 원인 분류

- 제품 오류: 코드나 계약이 기대 동작과 다르다. baton-cal-flows 기준으로 수정한다.
- 테스트 오류: 순서·시간·준비 대기 문제다. 예를 들어 모의 수신기의 HTTP 준비 전에 조회하면 실패한다.
  대기 조건을 명확히 고치고 시간 초과를 늘리는 것으로 끝내지 않는다.
- 환경·도구 오류: 러너, Docker, 액션 버전, 네트워크, 레지스트리 문제다. 같은 SHA의 재실행 이력은
  `gh run view <실행 ID> --attempt <번호>`로 확인하고, 재실행은 승인을 받은 뒤 `gh run rerun <실행 ID> --failed`로 한다.

원인을 확인하기 전에 전체 검증으로 넓히거나 같은 명령을 반복하지 않는다.

## 수정과 기록

- 조사만 요청받았으면 실패 단계, 로그 근거, 원인 분류, 수정안을 보고하고 멈춘다.
- 수정 요청이 있으면 실패한 범위부터 로컬 검증한다. main 실패는 새 `codex/` 브랜치와 PR로 고치며 main에 직접 푸시하지 않는다.
- `HANDOFF.md` 최근 검증에 실패 원인, 수정, 재검증 명령·결과·로그 경로를 기록한다. 재사용한 결과는 근거와 함께 구분한다.
- 푸시 후에는 필수 CI 완료를 확인한다. 통과 전에는 해결로 보고하지 않는다.
