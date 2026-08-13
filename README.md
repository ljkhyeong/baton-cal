# BATON CAL

BATON CAL은 BATON이 확정한 시즌 일정, 운영 회차와 마감을 읽기 전용 iCalendar 피드로
투영하는 독립 서비스다.

> 현재 상태: 시즌 단위 MVP 애플리케이션과 계약 테스트가 구현되어 있다. BATON 발행 측 연동과
> 공개 배포는 아직 하지 않았다. 공개 저장소는
> [ljkhyeong/baton-cal](https://github.com/ljkhyeong/baton-cal)이다.

## 서비스 경계

CAL이 소유한다.

- 원문을 저장하지 않는 해시 기반 구독 토큰과 폐기·회전 생명주기
- BATON 일정 스냅샷의 캘린더 투영
- 안정적인 iCalendar `UID`, `SEQUENCE`와 취소 표식
- `.ics` 피드, `ETag`, `Last-Modified`와 조건부 GET
- 원천 이벤트 수신함, 멱등성, 재전달과 투영 재구축 상태
- 피드 조회의 최소 운영 관측

CAL이 소유하지 않는다.

- 시즌 시간대, 반복 규칙, 회차 생성과 실제 마감 계산: BATON
- `AccountMembership`, 피드 발급·폐기 권한과 최종 접근 판단: BATON
- 이메일·메시지와 제공자 재시도: BATON RELAY
- 공개 앱 링크의 코드·만료·폐기: BATON GO
- ROUND 방 참여 자격과 참여 권한 증서: BATON과 ROUND

## 첫 MVP

1. BATON이 트랜잭션 커밋 이후 전달한 확정 일정 스냅샷만 수신한다.
2. 시즌 범위의 폐기 가능한 읽기 전용 구독을 만든다.
3. 안정적인 `UID`, 원본 개정 번호 기반 `SEQUENCE`와 취소 이벤트를 가진 `.ics`를 제공한다.
4. `ETag`와 `Last-Modified`로 캘린더 클라이언트의 반복 조회를 효율적으로 처리한다.
5. 동일 내용 재전달, 순서가 뒤바뀐 갱신, 토큰 회전과 전체 재구축을 검증한다.

## 보안 원칙

- 작업공간 키, 계정 세션, ROUND 권한 증서와 제공자 자격 증명을 캘린더 URL에 넣지 않는다.
- 구독 토큰 원문은 저장하지 않고 로그나 메트릭 레이블에 남기지 않는다.
- 캘린더 설명에는 최소 정보와 권한이 필요 없는 위치 식별자만 포함한다.
- 캘린더 클라이언트의 조회는 BATON의 권한 판단을 우회하지 않는다.

## 문서

- [제품 기준](docs/PRD/0001_product-baseline/spec.md)
- [MVP 실행 계약](docs/PRD/0002_mvp-contract/spec.md)
- [마이크로서비스 경계](docs/ADR/0001_microservice-boundary/adr.md)
- [기술 스택 결정](docs/ADR/0002_technology-stack/adr.md)
- [기계 판독형 계약](contracts/README.md)
- [다음 작업](HANDOFF.md)

## 기술 스택

- Kotlin 2.3.21, Java 25, Gradle 9.6.1
- Spring Boot 4.1.0, Spring MVC, `JdbcClient`, Bean Validation
- PostgreSQL 18.4, Flyway, Testcontainers
- iCal4j 4.2.5

## 로컬 실행

Java 25와 Docker가 필요하다. 저장소 루트에서 PostgreSQL을 먼저 시작한다.

```shell
docker compose up -d postgres
```

그다음 로컬 전용 내부 베어러를 환경 변수로 주입해 애플리케이션을 실행한다.

```shell
BATON_CAL_INTERNAL_TOKEN=local-development-internal-token-change-me ./gradlew --no-daemon bootRun
```

기본 상태 확인 엔드포인트는 `http://localhost:8080/actuator/health`이며 PostgreSQL은 로컬
루프백의 `5432` 포트에만 바인딩된다. 종료할 때는 다음 명령을 사용한다.

```shell
docker compose down
```

Docker 데몬이 실행 중인 환경에서 전체 검증은 다음 명령으로 실행한다.

```shell
./gradlew --no-daemon test
```
