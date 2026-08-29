# BATON CAL 계약 릴리스 절차

이 문서는 계약 ZIP을 검증하고 GitHub 불변 릴리스로 게시하는 표준 절차다. 현재 게시 상태와 자산
해시는 [계약 릴리스 현황](contract-release-history.md)에 기록한다.

## 공통 원칙

- `contracts/VERSION`을 ZIP 파일명과 `contracts-v{버전}` 태그의 단일 원천으로 사용한다.
- 계약 변경을 풀 리퀘스트로 검토해 `main`에 반영한 뒤, 깨끗하고 원격과 같은 커밋에서 게시한다.
- 과거 태그에서 갈라져 `main`에 병합하지 않는 호환 브랜치는 풀 리퀘스트의 `계약 릴리스 HEAD 검증`
  작업으로 합성 merge commit이 아닌 브랜치의 정확한 커밋과 계약 ZIP을 검증한다.
- `verifyContractsZip`으로 실제 ZIP의 파일명, 내부 버전과 포함 파일 목록을 검증한다.
- GitHub 저장소의 릴리스 불변성 설정을 켠 상태에서 초안을 검토한 뒤 게시한다.
- 게시한 태그와 자산은 교체하지 않는다. 수정이 필요하면 다음 버전을 만든다.
- BATON은 릴리스 증명과 자산을 검증하고 태그·파일명·SHA-256을 함께 고정한다.

## `1.0.1` 호환 보완판 준비

`contracts-v1.0.0`의 계약 ZIP에는 루트 `LICENSE`가 없다. `1.0.1`은 `1.0.0`의 스키마, 예시,
골든과 PRD 의미를 그대로 두고 라이선스와 새 버전 표식만 추가하는 호환 보완판이다. 현재 배치가
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
./gradlew --no-daemon clean verifyContractsZip
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
./gradlew --no-daemon clean verifyContractsZip
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

검증한 태그 커밋과 SHA-256을 계약 릴리스 현황에 기록하고 BATON이 `1.0.1`로 다시 고정한 뒤
생산자 계약 테스트를 실행한다. 이 작업은 `1.1.0-rc.1`의 새 계약 의미 검증을 대신하지 않는다.

## `1.1.0-rc.1` 게시

후보 변경이 `main`에 반영되고 로컬 `main`이 `origin/main`과 같은지 확인한다. 아래 두 `git status`
명령은 모두 아무 내용도 출력하지 않아야 한다.

```shell
git switch main
git pull --ff-only
test "$(git rev-parse HEAD)" = "$(git rev-parse '@{upstream}')"
git status --short
./gradlew --no-daemon clean test bootJar verifyContractsZip
git status --short
git tag -a contracts-v1.1.0-rc.1 -m "계약 1.1.0-rc.1"
test "$(git rev-parse HEAD)" = "$(git rev-parse 'contracts-v1.1.0-rc.1^{}')"
git push origin contracts-v1.1.0-rc.1
gh release create contracts-v1.1.0-rc.1 \
  build/distributions/baton-cal-contracts-1.1.0-rc.1.zip \
  --draft --prerelease --verify-tag --title "BATON CAL 계약 1.1.0-rc.1" \
  --notes-file docs/releases/contracts-v1.1.0-rc.1.md
```

초안을 확인하고 사전 릴리스로 게시한 뒤 릴리스와 자산 증명을 검증한다.

```shell
gh release edit contracts-v1.1.0-rc.1 --draft=false --prerelease=true
gh release verify contracts-v1.1.0-rc.1
gh release verify-asset contracts-v1.1.0-rc.1 \
  build/distributions/baton-cal-contracts-1.1.0-rc.1.zip
gh release view contracts-v1.1.0-rc.1 --json assets \
  --jq '.assets[] | select(.name == "baton-cal-contracts-1.1.0-rc.1.zip") | .digest'
```

BATON은 이 사전 릴리스의 버전·태그·자산·SHA-256을 고정해 교차 서비스 테스트를 실행한다. 계약
의미를 고쳐야 하면 게시한 RC를 바꾸지 않고 다음 RC를 만든다. 의미 변경 없이 통과하면 버전 표식을
안정 버전으로 올려 새 태그와 새 자산을 게시한다.
