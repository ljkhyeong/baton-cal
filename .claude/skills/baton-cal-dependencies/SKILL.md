---
name: baton-cal-dependencies
description: BATON CAL의 Gradle 플러그인·라이브러리, Gradle Wrapper, GitHub Actions, Compose 이미지 버전을 올리거나 Dependabot PR을 검토할 때 사용한다. 잠금 파일·검증 메타데이터 갱신과 iCal4j 변경의 골든·ETag 검토를 포함한다.
---

# BATON CAL 의존성 갱신

정식 절차는 `contracts/README.md`의 `의존성과 골든 갱신 절차`다. 버전은 `build.gradle.kts`에 직접 선언하며
Spring Boot BOM이 관리하는 라이브러리는 Boot 버전을 따른다.

## 원칙

- Spring Boot, Kotlin, iCal4j, JSON Schema 검증기는 한 종류씩 올린다. Dependabot의 `kotlin-plugins` 그룹은
  Kotlin `jvm`·`plugin.spring`을 함께 올린다.
- `gradle.lockfile`과 `gradle/verification-metadata.xml`을 함께 갱신하고, 바뀐 항목이 의도한 의존성과
  전이 의존성에만 해당하는지 diff로 확인한다.
- GitHub Actions는 커밋 SHA로 고정하고 뒤에 `# v<주 버전>` 주석을 둔다.
- Compose 이미지는 버전 태그와 `@sha256` digest를 함께 바꾼다. 알림 채널 스모크는 Compose 정의에서 이미지를
  읽으므로 따로 고치지 않는다. 테스트 PostgreSQL 이미지(`support/PostgreSqlTestContainer.kt`)는 Compose와 함께 바꾼다.
- Gradle Wrapper는 `distributionSha256Sum`을 유지한다.

## Gradle 의존성

```bash
BATON_CAL_GRADLE_HOME="$(mktemp -d /tmp/baton-cal-gradle.XXXXXX)"
GRADLE_USER_HOME="$BATON_CAL_GRADLE_HOME" ./gradlew --no-daemon \
  --write-locks --write-verification-metadata sha256 help dependencies
git diff -- gradle.lockfile gradle/verification-metadata.xml
./gradlew --no-daemon test --tests io.baton.cal.contract.ContractArtifactsTest
./gradlew --no-daemon test --tests io.baton.cal.calendar.IcsCalendarRendererTest
```

깨끗한 Gradle 저장소를 쓰는 이유는 기존 로컬 캐시에 가려진 플러그인 메타데이터까지 기록하기 위해서다.
계약·캘린더 테스트가 통과하면 `./gradlew --no-daemon check bootJar`로 넓힌다.

| 변경 | 추가 확인 |
| --- | --- |
| Spring Boot·Jackson | `web.SnapshotInputContractTest`의 입력 엄격성, `web.ApiExceptionHandlerTest`의 오류 응답, 설정에 적지 않고 기본값에 맡긴 health probes(`web.OperationalHttpTest`)·TLS 활성화(`config.TlsHttpIntegrationTest`)·`server.shutdown=graceful`(Boot 설정 메타데이터 기본값) |
| iCal4j | 아래 골든·시간대 검토. 출력이나 허용 TZID가 바뀌면 새 계약 후보가 필요하다 |
| JSON Schema 검증기 | `contract.ContractArtifactsTest`의 `format` 평가와 거부 사례 |
| Flyway·PostgreSQL 드라이버·Testcontainers | `persistence.PersistenceRepositoryTest` |
| ArchUnit | `architectureTest` |
| Spring Boot 이미지 빌더 | `bootBuildImage` 후 `./scripts/smoke-oci-image.sh <이미지>` |

## 골든과 시간대

- 골든이 달라져도 테스트에서 자동으로 덮어쓰지 않는다. 후보 `.ics`를 임시 위치에 만들어 속성·컴포넌트 순서,
  TEXT 재파싱 결과, TZID·`VTIMEZONE`, UTF-8 물리 줄 길이, `ETag` 변화를 검토한다.
- 의도한 호환성 변화만 `contracts/golden/*.ics.b64`에 반영하고 전체 테스트와 `bootJar`를 확인한다.
- iCal4j 버전이나 내장 Olson 데이터가 바뀌면 PRD-0002·ADR-0002·`contracts/README.md`·README의
  `iCal4j 4.3.0`·`2025a` 표기를 함께 고치고 baton-cal-contract-release에 따라 새 계약 후보를 만든다.

## Dependabot PR

1. `gh pr view <번호>`와 `gh pr diff <번호>`로 바뀐 의존성과 그룹을 확인한다.
2. 잠금 파일과 검증 메타데이터가 함께 바뀌었는지 확인하고, 빠졌으면 위 Gradle 절차로 생성한다.
3. 위 표의 추가 확인과 PR 필수 CI 결과를 본다. Actions·Compose 변경은 CI의 이미지·운영 스모크로 검증한다.
4. 릴리스 노트에서 동작 변경·보안 수정을 확인해 PR 검토 의견에 적는다. 병합은 사용자 요청이 있을 때만 한다.
