# BATON CAL 계약 릴리스 절차

이 문서는 계약 ZIP을 검증하고 GitHub 불변 릴리스로 게시하는 표준 절차다. 현재 게시 상태와 자산
해시는 [계약 릴리스 현황](contract-release-history.md)에 기록한다.

## 공통 원칙

- ZIP 파일명과 `contracts-v{버전}` 태그는 `contracts/VERSION` 값을 사용한다.
- 계약 변경을 풀 리퀘스트로 검토해 `main`에 반영한다. 미커밋 변경이 없고 로컬과 원격의 커밋이 같을 때 게시한다.
- 과거 태그에서 갈라져 `main`에 병합하지 않는 호환 브랜치는 풀 리퀘스트의 `계약 릴리스 HEAD 검증`
  작업으로 합성 merge commit이 아닌 브랜치의 정확한 커밋과 계약 ZIP을 검증한다.
- `verifyContractsZip`으로 실제 ZIP의 파일명, 내부 버전과 포함 파일 목록을 검증한다.
- GitHub 저장소의 릴리스 불변성 설정을 켠 상태에서 초안을 검토한 뒤 게시한다.
- 게시한 태그와 자산은 교체하지 않는다. 수정이 필요하면 다음 버전을 만든다.
- BATON은 릴리스 증명과 ZIP을 검증하고 사용할 태그·파일명·SHA-256을 지정한다.

## `1.0.1` 호환 보완판 준비

`contracts-v1.0.0`의 계약 ZIP에는 루트 `LICENSE`가 없다. `1.0.1`은 `1.0.0`의 스키마, 예시,
골든 파일과 PRD의 계약 의미를 유지하고 라이선스와 새 버전 표식만 추가하는 호환 보완판이다. 필요한 빌드 설정이
`main`에 반영된 뒤 다음과 같이 별도 작업 트리를 만든다.

```shell
git fetch origin main --tags
git worktree add ../baton-cal-contracts-1.0.1 contracts-v1.0.0
cd ../baton-cal-contracts-1.0.1
git switch -c release/contracts-v1.0.1
git restore --source=origin/main -- \
  LICENSE build.gradle.kts gradle.lockfile gradle/verification-metadata.xml \
  gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties gradlew gradlew.bat \
  docs/releases/contracts-v1.0.1.md
```

`contracts/VERSION`을 `1.0.1`로 바꾸고 `contracts/README.md`의 현재 버전, ZIP 파일명과 태그를
`1.0.1`로 맞춘다. README에는 `LICENSE` 포함과 `1.0.0` 계약 의미를 그대로 재포장했다는 사실만
추가한다. 스키마, 예시, 골든과 PRD가 바뀌지 않았는지는 다음 명령으로 확인한다.

```shell
git diff --exit-code contracts-v1.0.0 -- \
  contracts/examples contracts/golden contracts/schemas docs/PRD/0002_mvp-contract/spec.md
./gradlew --no-daemon verifyContractsZip
git add LICENSE build.gradle.kts contracts gradle gradlew gradlew.bat docs/releases/contracts-v1.0.1.md
git commit -m "배포: 계약 1.0.1 호환 보완판 준비"
git push -u origin release/contracts-v1.0.1
```

풀 리퀘스트에서 변경 범위, 일반 CI와 `계약 릴리스 HEAD 검증` 작업을 모두 확인한다. 후자는
`release/contracts-` 브랜치의 정확한 HEAD에서 만든 계약 ZIP을 별도 산출물로 올린다. 이 호환
브랜치는 과거 안정 태그에서 갈라지므로 `main`에 병합하지 않고 릴리스 태그의 기준 커밋으로만 사용한다.

## `1.0.1` 게시

호환 브랜치의 풀 리퀘스트 검토와 CI가 끝나면 같은 커밋을 로컬에 체크아웃하고 다음 선행 조건을
확인한다. `git status --short`는 아무 내용도 출력하지 않아야 한다.

```shell
git switch release/contracts-v1.0.1
git pull --ff-only
test "$(git rev-parse HEAD)" = "$(git rev-parse '@{upstream}')"
git status --short
./gradlew --no-daemon verifyContractsZip
git status --short
git tag -a contracts-v1.0.1 -m "계약 1.0.1"
test "$(git rev-parse HEAD)" = "$(git rev-parse 'contracts-v1.0.1^{}')"
git push origin contracts-v1.0.1
gh release create contracts-v1.0.1 build/distributions/baton-cal-contracts-1.0.1.zip \
  --draft --verify-tag --title "BATON CAL 계약 1.0.1" \
  --notes-file docs/releases/contracts-v1.0.1.md
```

초안의 태그, 제목, 릴리스 노트와 자산 하나를 확인한 뒤 게시한다.

```shell
gh release edit contracts-v1.0.1 --draft=false
gh release verify contracts-v1.0.1
gh release verify-asset contracts-v1.0.1 build/distributions/baton-cal-contracts-1.0.1.zip
gh release view contracts-v1.0.1 --json assets \
  --jq '.assets[] | select(.name == "baton-cal-contracts-1.0.1.zip") | .digest'
```

검증한 태그 커밋과 SHA-256을 계약 릴리스 현황에 기록한다. BATON의 사용 버전을 `1.0.1`로 바꾸고
계약 테스트를 실행한다. 새 계약 후보는 별도로 검증한다.

## 새 사전 릴리스 게시

`1.1.0-rc.1`은 이미 게시됐다. 새 후보 버전은 `contracts/VERSION`에서 읽고, 해당 버전의
`docs/releases/contracts-v{버전}.md`를 준비한다. 현재 후보 `1.1.0-rc.2`의 릴리스 노트 작성은 남아 있다.
게시한 태그와 자산은 교체하지 않으며, 릴리스 노트에는 새 버전의 변경 사항을 반영한다.

후보 변경과 릴리스 노트가 `main`에 반영되고 로컬 `main`이 `origin/main`과 같은지 확인한다.
아래 명령은 같은 셸에서 순서대로 실행하고 실패하면 중단한다. 두 `git status` 결과는 모두 비어 있어야 한다.
검증 재실행 기준은 [개발 검증 절차](development.md)를 따른다.

```shell
git switch main
git pull --ff-only
test "$(git rev-parse HEAD)" = "$(git rev-parse '@{upstream}')"
contract_version="$(cat contracts/VERSION)"
release_tag="contracts-v${contract_version}"
release_zip="build/distributions/baton-cal-contracts-${contract_version}.zip"
release_notes="docs/releases/${release_tag}.md"
test -f "$release_notes"
git status --short
./gradlew --no-daemon test bootJar verifyContractsZip
git status --short
git tag -a "$release_tag" -m "계약 $contract_version"
test "$(git rev-parse HEAD)" = "$(git rev-parse "$release_tag^{}")"
git push origin "$release_tag"
gh release create "$release_tag" "$release_zip" \
  --draft --prerelease --verify-tag --title "BATON CAL 계약 $contract_version" \
  --notes-file "$release_notes"
```

초안을 확인하고 사전 릴리스로 게시한 뒤 릴리스와 자산 증명을 검증한다.

```shell
gh release edit "$release_tag" --draft=false --prerelease=true
gh release verify "$release_tag"
gh release verify-asset "$release_tag" "$release_zip"
gh release view "$release_tag" --json assets --jq '.assets[] | {name, digest}'
```

BATON은 사용할 사전 릴리스의 버전·태그·ZIP·SHA-256을 지정하고 BATON과 CAL의 연동 테스트를 실행한다.
계약을 수정해야 하면 다음 RC를 만든다. 계약 수정 없이 통과하면 버전을 안정 버전으로 바꿔
새 태그와 ZIP을 게시한다.
