# ADR-0003: 파일 검사와 종료 전 구조 검사

- 상태: 채택됨
- 결정일: 2026-09-12

## 결정

파일 작성 직후에는 표준 파서·Git·증분 컴파일로 빠르게 검사한다. 종료 전에는 작업 시작 커밋부터의
전체 diff를 검토하고 [ArchUnit](https://www.archunit.org/userguide/html/000_Index.html)으로 운영 클래스의
의존성을 확인한다. import 문자열이나 파일명만 정규식으로 검사하지 않는다.

현재 Spring Repository 주입 구조는 유지한다. 검사만을 위한 Repository 인터페이스와 변환 DTO는
추가하지 않는다. 계층 규칙은 다음과 같다.

| 대상 | 금지하는 의존성 |
| --- | --- |
| Controller | Repository와 JDBC 직접 접근. Service가 반환한 데이터 객체 사용은 허용 |
| 일정·스냅샷·복구 도메인 | persistence·web·config 및 Spring 실행 계층 의존 |
| Service | JDBC 직접 사용, Repository 생성자 호출. Spring Repository 주입은 허용 |

도메인은 `calendar`, `snapshot`, `recovery` 패키지와 하위 도메인 패키지를 기준으로 한다. 현재 같은
패키지의 `IcsCalendarRenderer`, `SnapshotIngestionService`, `RecoveryManifestService`와 해당 Kotlin
생성 클래스는 실행 계층으로 제외한다. 새 도메인 클래스는 자동으로 검사되며, 새로운 실행 계층을
이 패키지에 추가할 때는 분류를 검토해야 한다. 기존 위반을 일괄 허용하는 기준 파일은 만들지 않는다.

`architectureTest`는 DB 없이 실행한다. 별도 JUnit 엔진 없이 ArchUnit core를 기존 JUnit에서 사용하며,
일반 `test`와 중복 실행하지 않는다. 규칙 위반 코드가 탐지되고 정상 Repository 주입은 통과하는지 함께
검증한다. `check`와 기존 CI에 연결해 에이전트 훅 사용 여부와 관계없이 적용한다.

Codex 훅과 수동 명령은 같은 Python 스크립트를 사용한다. 입력과 실행 결과가 같은 파일 검사는 생략하고,
Gradle의 증분 빌드·테스트 캐시를 사용한다. 로그와 전체 diff는 Git에서 제외된 `build/` 아래 보관한다.
실제 실행 방법과 훅 신뢰 절차는 [개발 검증 절차](../../development.md)를 따른다.

## 범위

자동 규칙은 선언한 의존성만 확인한다. 비즈니스 책임의 적절성, 과한 추상화, 계약 변경 누락은 전체
diff 검토와 관련 기능 테스트로 확인한다. 제품 동작과 공개 계약은 변경하지 않는다.
