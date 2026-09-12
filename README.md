# BATON CAL

BATON CAL은 확정된 시즌 일정·회차·마감을 읽기 전용 캘린더 구독(.ics)으로 제공하는 독립 서비스다.

> 현재 상태: 시즌 단위 MVP 애플리케이션과 계약 테스트가 구현되어 있고 안정 계약 `1.0.0`의
> BATON의 계약 테스트를 통과했다. 실제 운영 활성화와 공개 배포는 아직 하지 않았다. 공개 저장소는
> [ljkhyeong/baton-cal](https://github.com/ljkhyeong/baton-cal)이다.

문서에서 **투영**은 확정 일정을 변환해 저장한 캘린더 데이터다. **골든 파일**은 출력 비교에 쓰는
[기준 `.ics` 바이트](contracts/golden)를 Base64로 보관한 파일이다.

## 서비스별 역할

CAL이 담당한다.

- 구독 토큰 발급·폐기·재발급, 원문 대신 해시 저장
- 확정된 BATON 일정을 iCalendar로 변환
- 일정의 `UID` 유지, 개정 번호를 나타내는 `SEQUENCE`와 취소 표식 관리
- `.ics` 피드, 본문 없는 상태 확인(HEAD), `ETag`, `Last-Modified`와 조건부 조회
- 일정 이벤트 수신 기록과 멱등 처리, 재전달·캘린더 재생성 상태
- 구독 요청·오류 모니터링

다른 서비스가 담당한다.

- 시즌 시간대, 반복 규칙, 회차 생성과 실제 마감 계산: BATON
- `AccountMembership`, 피드 발급·폐기 권한과 최종 접근 판단: BATON
- 이메일·메시지와 제공자 재시도: BATON RELAY
- 공개 앱 링크의 코드·만료·폐기: BATON GO
- ROUND 방 참여 자격과 참여 권한 증서: BATON과 ROUND

## 첫 MVP

1. BATON이 트랜잭션 커밋 이후 전달한 확정 일정 스냅샷만 수신한다.
2. 시즌별로 해제할 수 있는 읽기 전용 구독을 만든다.
3. 같은 일정의 `UID`를 유지하고 원본 개정 번호를 `SEQUENCE`에 반영하며, 취소 표식을 담은 `.ics`를 제공한다.
4. `ETag`와 `Last-Modified`로 캘린더 클라이언트의 반복 조회를 효율적으로 처리한다.
5. 동일 내용 재전달, 순서가 뒤바뀐 갱신, 토큰 회전과 전체 재구축을 검증한다.

## 보안 원칙

- 작업공간 키, 계정 세션, ROUND 권한 증서와 제공자 자격 증명을 캘린더 URL에 넣지 않는다.
- 구독 토큰 원문은 저장하지 않고 로그나 메트릭 레이블에 남기지 않는다.
- 캘린더 설명에는 최소 정보와 권한이 필요 없는 위치 식별자만 포함한다.
- 캘린더 클라이언트의 조회는 BATON의 권한 판단을 우회하지 않는다.

## 운영 기본 설정

- 일정 묶음 수신은 최대 100건을 한 트랜잭션으로 처리하고, 변경된 시즌의 캘린더를 한 번씩 만든다.
  전체 JSON 128 KiB 제한을 함께 적용하며 오류가 있으면 묶음 전체를 취소한다.
- JSON 요청은 개별 DTO·JSON Schema 필드 제약과 별개로 전체 문서 128 KiB(131,072바이트),
  필드명 64자, 중첩 16단계, 숫자 10자리와 토큰 8,192개까지 파싱한다. 이 요청 제한을 하나라도
  넘으면 기존 계약과 같은 `413`, `REQUEST_TOO_LARGE`,
  `request body exceeds the maximum size`를 반환한다.
- 개정 번호와 건수는 소수점·지수·따옴표 없는 정수로 보낸다. `0.5`, `1.0`, `1e0`, `"1"`은
  `400 INVALID_REQUEST`로 거부하며, 타임스탬프의 소수 초는 기존대로 허용한다.
- 제목·설명·장소·시즌 이름 등 문자열 필드에는 JSON 문자열을 보낸다. 숫자·불리언을 문자열로 바꾸지 않고
  `400 INVALID_REQUEST`로 거부한다. `"123"`, `"true"`처럼 따옴표로 감싼 문자열은 허용한다.
- 공개 피드 기준 URL은 외부 또는 비루프백 주소에서 HTTPS만 허용한다. 루프백 HTTP는 로컬
  개발에서만 허용하며 `prod` 프로필은 `BATON_CAL_PUBLIC_BASE_URL`을 명시하지 않거나 HTTPS가
  아니면 시작에 실패한다.
- 로컬 실행은 `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`의 개발 기본값을 제공한다.
  `prod` 프로필은 세 값을 모두 외부 환경에서 명시하지 않으면 시작에 실패한다.
- DB 비밀번호와 내부 토큰은 Spring Boot의 `configtree`로 k3s Secret 파일에서 읽을 수 있다.
  파일 이름과 재시작 기준은 [Secret 파일 연결](docs/operations.md#secret-파일-연결)을 따른다.
- PostgreSQL 잠금 대기는 기본 5초, SQL 실행과 Spring 트랜잭션은 기본 30초로 제한한다.
  `DATABASE_LOCK_TIMEOUT`, `DATABASE_STATEMENT_TIMEOUT`, `DATABASE_TRANSACTION_TIMEOUT`으로 환경에
  맞게 조정한다. DB 연결·트랜잭션 시작 실패, 교착 상태·직렬화 실패, 잠금·SQL·트랜잭션 시간 초과에는
  `503 SERVICE_BUSY`, `Retry-After: 1`, `Cache-Control: no-store`를 반환한다. 호출자는 헤더에 맞춰 재시도한다.
- Tomcat 접근 로그는 기본적으로 끄고, 나중에 켜더라도 경로·쿼리·헤더를 기록하지 않는 패턴을
  기본값으로 둔다. `prod` 프로필에서는 `StatementCreatorUtils` 로그를 끈다.
- 공개 `/calendars/v1/**` 요청을 추적할 때 `http.url`에는 실제 토큰 대신
  `/calendars/v1/{token}.ics`를 기록한다.
- 일정 수신 결과는 `baton.cal.snapshot.ingestion`, 투영 재구축 시간·항목 수·캘린더 크기(바이트)는
  `baton.cal.projection.rebuild`, `baton.cal.projection.items`, `baton.cal.projection.bytes`, 시즌
  잠금 획득 시간은 `baton.cal.projection.lock.acquire`로 기록한다. 내부 인증 결과는
  `baton.cal.internal.authentication`의 `current`, `previous`, `unauthorized` 세 값만 사용한다.
  토큰·시즌·항목 식별자는 메트릭 태그에 넣지 않는다.
- Prometheus 형식은 `/actuator/prometheus`에서 제공한다. `prod` 프로필은 관리 서버를 기본
  `8081` 포트로 분리하며, 공개 역방향 프록시는 이 포트를 노출하지 않고 메트릭 수집기만 접근하게 한다.
  포트는 `MANAGEMENT_SERVER_PORT`로 바꿀 수 있다.
- 내부 Bearer는 필수 현재 값 `BATON_CAL_INTERNAL_TOKEN`과 회전할 때만 쓰는 선택적 이전 값
  `BATON_CAL_PREVIOUS_INTERNAL_TOKEN`을 최대 두 개까지 허용한다. 두 값은 모두 32자 이상이어야
  하고 RFC 6750 `b64token` 문자 범위와 끝의 `=` 패딩만 사용한다. 선택적 값을 빈 문자열로 설정하면
  시작에 실패한다. HTTP `Bearer` 스킴은 대소문자를 구분하지 않고 인증 실패 `401`은 고정된
  `WWW-Authenticate` challenge를 반환한다.
- 공개 구독은 DB 상태가 `ACTIVE`이고 토큰 해시와 저장된 구독 세대가 모두 일치할 때만 조회된다.
  외부 런타임 값 `BATON_CAL_SUBSCRIPTION_GENERATION`은 비밀이 아닌 UUID이며 정상 재시작·일반
  배포에서는 같은 값을 유지한다.
  호환용 초기값은 `00000000-0000-0000-0000-000000000001`이고, `prod` 프로필은 환경 변수로
  값을 명시하지 않으면 시작에 실패한다.
- `BATON_CAL_RECOVERY_MODE`의 기본값은 `false`다. `true`이면 구독 생성·회전만
  `503 RECOVERY_IN_PROGRESS`로 차단하고 수신·재구축·폐기·공개 피드·readiness는 유지한다.
  복구 모드는 기존 URL을 무효화하지 않으므로 과거 DB 복원 때의 구독 세대 교체도 반드시 수행한다.
  설정 변경에는 모든 인스턴스의 재시작이 필요하며 자동 해제하지 않는다.
- 시간대 지정 현지 시각은 iCal4j 4.3.0 내장 Olson `2025a`가 원문 식별자로 제공하는 TZID만
  허용한다. DST 공백도 같은 `VTIMEZONE`에서 만든 `ZoneRules`로 판정해 Java 런타임의 별도 TZDB와
  규칙을 섞지 않는다. iCal4j나 내장 시간대 데이터를 올릴 때는 골든 바이트와 ETag를 검토한다.

실제 역방향 프록시와 추적 내보내기의 경로·쿼리·헤더 삭제 처리는 공개 배포 전에 실제 환경에서
검증해야 한다.

### 내부 Bearer 회전

운영용 내부 Bearer는 다음 표준 명령으로 새 값을 발급한다.

```shell
openssl rand -hex 32
```

회전할 때는 새 값을 `BATON_CAL_INTERNAL_TOKEN`, 기존 값을
`BATON_CAL_PREVIOUS_INTERNAL_TOKEN`으로 넣어 CAL을 먼저 배포한다. 그다음 BATON 호출자를 새 값으로
전환하고, 이전 값을 쓰는 요청이 없음을 확인한 즉시 `BATON_CAL_PREVIOUS_INTERNAL_TOKEN`을 제거해
CAL을 다시 배포한다. 구현은 제시된 자격 증명을 설정된 모든 값과 상수 시간으로 비교한다. 임의 개수의
토큰 목록을 만들거나 이전 값을 장기간 유지하지 않는다. 외부 메트릭 수집기를 연결한 환경에서는
`baton.cal.internal.authentication{result="previous"}` 증가가 멈춘 것을 제거 판단의 근거로 쓴다.

### V4~V7 최초 배포

구독 세대를 처음 도입하는 V4는 유지보수 배포다. 모든 pre-V4 CAL 인스턴스를 먼저 중지하고
`BATON_CAL_SUBSCRIPTION_GENERATION=00000000-0000-0000-0000-000000000001`로 신버전만 시작한다.
V4는 기존 구독에 이 초기 세대 값을 저장하므로 같은 값을 써야 기존 피드 URL이 유지된다.

V4 적용 뒤에는 세대를 검사하지 않는 pre-V4 바이너리를 다시 시작하거나 그 버전으로 롤백하지 않는다.
문제가 생기면 신버전의 오류를 수정해 재배포하거나, 신버전과 아래 복원 절차를 사용한다. V4 적용 중에는 구·신
버전을 함께 서비스하지 않는다. V4 배포가 끝난 뒤의 일반 배포는 같은 세대를 유지한다.

V5는 더 이상 읽지 않는 `season_feed_projection.item_count`를 제거한다. 이 열을 계속 읽고 쓰는
pre-V5 인스턴스를 모두 중지한 뒤 신버전만 시작하고, 적용 뒤 pre-V5 롤백이나 구·신 버전 공존은
금지한다. 문제가 생기면 신버전의 오류를 수정해 재배포한다.

V6는 UTC·시간대 지정 시점과 종일 날짜 구간을 추가한다. 기존 구간 행은 그대로 유지되지만
pre-V6 인스턴스는 새 시간 형태를 읽지 못하므로, 모든 pre-V6 인스턴스를 종료한 뒤 BATON이 새
형태를 보내야 한다. 새 형태를 수신한 뒤에는 pre-V6 롤백이나 구·신 버전 공존을 금지한다.

V7는 시즌 표시 이름 테이블만 추가하고 기존 피드는 변경하지 않는다. 모든 pre-V7 인스턴스를 종료한
뒤 BATON의 이름 전달을 시작한다. 구버전은 재구축 때 기본 이름을 사용하므로 이름 전달을 시작한
뒤에는 pre-V7 롤백이나 구·신 버전 공존을 금지한다.

V8은 복구 실행의 시즌별 검증 결과와 전체 완료 신호를 저장한다. 새 내부 경로를 처리하는 모든
인스턴스가 V8 스키마를 공유해야 하므로 마이그레이션이 끝나기 전에 복구 검증을 시작하지 않는다.

### 과거 DB 백업 복원

과거 백업을 복원할 때는 CAL을 중지한 상태로 두고 다음 순서를 지킨다. 표준 UUID 생성 명령은
`uuidgen`이며 별도 생성기를 구현하지 않는다.

```shell
uuidgen
```

1. 생성한 값이 NIL UUID `00000000-0000-0000-0000-000000000000`이 아니고 이전에 사용하지 않은
   값인지 확인한다.
2. DB를 복원하기 전에 모든 인스턴스의 외부 런타임 설정에서 `BATON_CAL_SUBSCRIPTION_GENERATION`을
   새 값으로 바꾸고 `BATON_CAL_RECOVERY_MODE=true`를 설정한다.
3. CAL이 중지된 상태에서 과거 DB를 복원한다.
4. 새 세대와 복구 모드 설정으로 CAL을 시작한다. 구독 생성·회전은 `503 RECOVERY_IN_PROGRESS`로
   차단되며 일정 수신과 재구축은 가능하다.
5. BATON에서 새 `recoveryId`를 정하고 전달을 끈 상태로 최신 전체 일정·필요한 취소·시즌 이름을
   재전달 대기에 넣는다. 같은 `recoveryId`로 전달을 켜면 BATON이 모든 최신 아웃박스의 전달 완료를
   확인한 뒤 시즌별 매니페스트와 전체 완료 신호를 보낸다.
6. `PUT /internal/api/v1/recovery-runs/{recoveryId}/completion`의 `COMPLETED` 응답을 확인한 뒤
   모든 인스턴스에 `BATON_CAL_RECOVERY_MODE=false`를 적용해 재시작한다.
   `BATON_CAL_SUBSCRIPTION_GENERATION`은 새 값을 유지한다.
7. 기존 `subscriptionId`를 rotate하거나 새 구독을 create해 현재 세대
   자격 증명을 발급한다.

정상 재시작과 일반 배포에는 기존 세대를 유지하며 과거에 사용한 세대를 다시 사용하지 않는다.

복원된 DB의 구독은 이전 세대에 속하므로 기존 피드 URL은 즉시 본문 없는 일반 `404`가 된다.
BATON의 전체 최신 스냅샷 재전달이 끝나기 전에 현재 세대 자격 증명을 발급해서는 안 된다. CAL 내부
재구축만으로는 백업 시점 이후의 원본 최신성을 되찾을 수 없다.

저장소의 OCI 스모크는 이 순서를 대표 계약 픽스처로 실제 PostgreSQL 논리 백업·복원까지 훈련한다.
CAL은 복구 모드에서 시즌별 일정 수·다이제스트와 시즌 이름 개정·다이제스트를 현재 저장 상태에
대조하고, 검증한 시즌 집합이 현재 데이터의 시즌 집합과 정확히 같을 때만 전체 완료를 기록한다.
같은 완료 요청은 최초 완료 시각을 유지해 멱등하게 반환한다. 운영자 또는 상위 오케스트레이션은
이 완료 응답을 확인한 뒤 복구 모드를 해제해야 한다.
`RECOVERY_IN_PROGRESS`에는 완료 시각을 추측한 `Retry-After`를 넣지 않는다. 생성·회전 호출자는
모드 해제를 확인한 뒤 명시적으로 다시 요청한다. POST 생성·회전은 응답 유실을 이유로 자동
재시도하지 않으며 ID 지정 PUT 생성은 아래 복구 절차를 따른다.

### 구독 생성 응답 유실 복구

새 연동은 BATON이 구독 ID와 승인한 시즌 ID를 먼저 저장하고
`PUT /internal/api/v1/subscriptions/{subscriptionId}`에 `{seasonId}`를 보낸다. 최초 성공의 `201`만
토큰·피드 URL을 반환한다. 같은 ID·시즌은 `409 SUBSCRIPTION_ALREADY_EXISTS`, 다른 시즌은
`409 SUBSCRIPTION_SCOPE_CONFLICT`이며 기존 토큰·상태·세대를 바꾸지 않는다.
상태 조회의 `404`와 생성 충돌의 `409`에도 `Cache-Control: no-store`를 적용해 이전 오류 응답의 재사용을 막는다.

응답을 잃으면 같은 ID로 상태를 조회한다. `404`일 때는 같은 ID·시즌으로 PUT을 재전달할 수 있다.
활성 구독이 있지만 자격 증명을 받지 못했다면 사용자 요청에 따라 rotate로 다시 발급한다. 폐기된
구독은 새 ID로 생성한다. 토큰 원문은 계속 저장하지 않으며 회전 응답을 잃었을 때도 다시 명시적으로
재발급해야 한다. 기존 POST 생성에는 유실된 구독 ID를 찾을 수 없는 제한이 남는다.

새 PUT은 모든 CAL 인스턴스 배포와 BATON의 후보 계약 고정 뒤 활성화한다. CAL 구현만으로 BATON의
사전 ID 저장·응답 유실 안내까지 완료된 것은 아니다.

### 내부 상태 조회

내부 Bearer 인증으로 다음 상태를 조회할 수 있다. 성공 응답은 `Cache-Control: no-store`이며
복구 모드에서도 동작한다.

- `GET /internal/api/v1/calendar-items/{sourceItemId}`: CAL이 채택한 개정 번호, 일정 상태와 원본 수정 시각.
- `GET /internal/api/v1/subscriptions/{subscriptionId}`: 구독 상태와 현재 인스턴스의 구독 세대 일치 여부.
- `GET /internal/api/v1/recovery-runs/{recoveryId}`: 저장된 진행·완료 상태, 검증한 시즌 수, 최초 완료
  시각과 이 인스턴스의 복구 모드. 완료 기록은 이후 원본 변경에도 유지한다.
- `GET /internal/api/v1/seasons/{seasonId}/recovery-state`: 현재 일정 수·다이제스트와 이름 개정·
  다이제스트. BATON이 계산한 기대값과 비교하는 진단용이며 기대 매니페스트를 대신하지 않는다.

취소 일정과 폐기된 구독도 조회할 수 있다. 응답에는 구독 토큰·토큰 해시·피드 URL·세대 UUID를 넣지 않는다. 복구 다이제스트는 진단 대상이다.
복구 실행 조회는 저장된 완료 기록을 반환한다. 나머지 진단 값은 현재 BATON 전체 원본의 완전성이나
접근 권한을 증명하지 않으며 유실된 토큰도 복구하지 않는다.
필드와 오류의 기준은 [MVP 계약](docs/PRD/0002_mvp-contract/spec.md)의 HTTP 경로 절을 따른다.

### 시즌 표시 이름

`PUT /internal/api/v1/seasons/{seasonId}/calendar-metadata`에 내부 Bearer와
`{revision, displayName}`을 전달하면 같은 시즌의 피드 이름을 갱신한다. 개정 번호는 BATON이 일정
개정 번호와 별도로 관리하며, 응답은 CAL이 채택한 현재 이름·개정 번호다. 이름을 받지 않은 시즌은
기존 `BATON season {UUID}`를 유지한다.

이름 변경은 일정 UID·SEQUENCE와 구독 URL을 바꾸지 않는다. 같은 개정 번호의 다른 이름은 충돌이며,
중복·낮은 개정 번호는 현재 값을 반환한다. 복구 모드에서도 사용할 수 있다. BATON은 최초 시즌·
이름 수정·다음 시즌 생성에서 V29 전용 이름 아웃박스를 기록하고 기존 전달 작업자로 전송한다.
`BATON_CAL_SEASON_METADATA_ENABLED`는 기본 `false`이며 안정 계약 핀은 `1.0.0`을 유지한다.
BATON의 `BATON_CAL_SEASON_METADATA_MAINTENANCE`는 기본 `OFF`다. `BACKFILL`은 기존 시즌과
캡처 중단 기간의 이름을 보정하고, `REPLAY`는 보정 뒤 최신 이름 행을 같은 개정 번호로 재전달 대기에
넣는다. 이름 연동·캡처를 켜고 전달을 끈 상태에서 준비한 뒤, 모드를 `OFF`로 되돌려 전달한다.
계약 오류로 실패한 행은 자동 재처리하지 않는다. 이 준비 완료 자체는 CAL 수신 완료가 아니며,
BATON이 같은 복구 ID로 최신 일정·시즌 이름 아웃박스의 전달 완료와 CAL 매니페스트 검증까지
마쳐야 전체 복구 완료가 된다. 후보 계약 채택과 실제 캘린더 앱 검증은 남아 있다.
자세한 제약은 [MVP 계약](docs/PRD/0002_mvp-contract/spec.md)을 따른다.

BATON에서 종료된 시즌의 원본 이름을 고쳐야 할 때는 운영자 복구 키로 보호한
`PATCH /api/v1/teams/{teamId}/seasons/{seasonId}/name`을 사용한다. CAL에 직접 이름이나 개정 번호를
만들어 보내지 않고 BATON 이름 아웃박스를 거친다. 같은 시즌의 더 높은 개정이 전달되면 BATON은
과거 이름 실패를 조치 대상에서 제외하되 실패 이력은 보존한다.

## 문서

- [제품 기준](docs/PRD/0001_product-baseline/spec.md)
- [MVP 실행 계약](docs/PRD/0002_mvp-contract/spec.md)
- [마이크로서비스 경계](docs/ADR/0001_microservice-boundary/adr.md)
- [기술 스택 결정](docs/ADR/0002_technology-stack/adr.md)
- [기계 판독형 계약](contracts/README.md)
- [개발 검증 절차와 스킬 검증 환경](docs/development.md)
- [계약 릴리스 현황](docs/contract-release-history.md)
- [시즌 투영·실제 HTTP 수신 성능 기준](docs/performance-baseline.md)
- [cal.b4ton.com HTTPS·프록시·모니터링 운영 구성](docs/operations.md)
- [캘린더 앱별 구독 안내와 호환성 확인표](docs/calendar-subscription-guide.md)
- [추가 요금 없는 외부 API 연동 기준](docs/external-api-options.md)
- [기능 추가·개선 검토](docs/reviews/2026-09-05-feature-review.md)
- [다음 작업](HANDOFF.md)

## 기술 스택

- Kotlin 2.4.10, Java 25, Gradle 9.7.1
- Spring Boot 4.1.1, Spring MVC, `JdbcClient`, Bean Validation
- Spring Boot Actuator, Micrometer Prometheus
- PostgreSQL 18.6, Flyway, Testcontainers
- iCal4j 4.3.0

## 로컬 실행

Java 25와 Docker가 필요하다. 저장소 루트에서 PostgreSQL을 먼저 시작한다.

```shell
docker compose up -d postgres
```

그다음 로컬 전용 내부 베어러를 환경 변수로 주입해 애플리케이션을 실행한다.

```shell
BATON_CAL_INTERNAL_TOKEN=local-development-internal-token-change-me ./gradlew --no-daemon bootRun
```

로컬 기본 공개 기준 URL은 `http://localhost:8080`이다. 기본 상태 확인 엔드포인트는
`http://localhost:8080/actuator/health`, Prometheus 메트릭은
`http://localhost:8080/actuator/prometheus`이며 PostgreSQL은 로컬 루프백의 `5432` 포트에만
바인딩된다. `prod` 프로필에서는 상태와 메트릭이 기본 `8081` 관리 포트로 분리된다. 종료할 때는
다음 명령을 사용한다.

```shell
docker compose down
```

변경별 검증 선택과 결과 재사용 기준은 [개발 검증 절차](docs/development.md)를 따른다.
Docker 데몬이 실행 중인 환경에서 일반 테스트와 계약 ZIP을 함께 검증하려면 다음 명령을 사용한다.

```shell
./gradlew --no-daemon check
```

실행 JAR 검증이 필요하면 `bootJar`를 추가한다. `projectionLoadTest`와 `ingestionLoadTest`는
[성능 측정](docs/performance-baseline.md)이 필요한 작업에서 별도로 실행한다.

## 계약 팩 검증과 배포

실제 Spring MVC 응답은 MockMvc로 일정 수신 결과, 일정·구독 상태 조회, 구독 생성·회전,
투영 재구축과 공통 오류를 실행한 뒤 각 v1 JSON Schema에 직접 대조한다. 따라서 예제 파일뿐 아니라 컨트롤러의 실제
직렬화 결과도 `additionalProperties: false`를 포함한 응답 계약을 따라야 한다. 시간대 일정은
취소 뒤 더 높은 개정 번호로 재활성화하고 같은 UID의 `SEQUENCE`와 `STATUS`를 확인한다. 예상 밖
`500` 응답은 고정 형식이며 예외 메시지의 비밀값을 응답과 애플리케이션 로그에 남기지 않는다.

BATON이 검토할 계약 팩은 Gradle 표준 `Zip` 작업으로 만들고 실제 산출물을 검증한다.

```shell
./gradlew --no-daemon verifyContractsZip
```

계약 버전은 `contracts/VERSION`에서 관리하며 현재 작업 후보는 `1.1.0-rc.2`이다. 따라서 결과는
`build/distributions/baton-cal-contracts-1.1.0-rc.2.zip`이고, ZIP 안에도 같은
`contracts/VERSION`이 들어간다. 후보 릴리스 태그는 `contracts-v1.1.0-rc.2`이며 파일명, ZIP 내부
버전과 태그가 모두 같은 버전을 가리켜야 한다. ZIP은 루트 `LICENSE`, `contracts/**` 전체와 필드 간
의미, HTTP 상태, 토큰과 iCalendar 규칙의 기준인 `docs/PRD/0002_mvp-contract/spec.md`를 포함한다.
파일 시각과 항목 순서, 권한을 고정해 같은 입력에서 같은 ZIP 바이트를 만들며, 별도 압축 스크립트나
수동 체크섬·매니페스트를 유지하지 않는다. 검증 작업은 생성된 ZIP의 파일명, 내부 버전과 포함 파일
목록이 소스와 같은지 확인한다.

GitHub Actions는 이 ZIP을 `upload-artifact`로 올리고 `retention-days: 90`으로 보존을 요청한다.
실제 만료는 저장소·조직 정책을 따르며, 이 파일은 변경 검토와 다운로드 확인을 위한 임시 CI
산출물이므로 BATON이 고정할 안정적인 의존성이 아니다. 과거 계약 태그에서 갈라져 `main`에
병합하지 않는 `release/contracts-` 풀 리퀘스트는 일반 merge 검증과 별도로 브랜치의 정확한 HEAD를
체크아웃해 계약 ZIP을 검증하고 별도 산출물로 올린다.

BATON이 사용하는 안정 계약은 [불변 릴리스 `contracts-v1.0.0`](https://github.com/ljkhyeong/baton-cal/releases/tag/contracts-v1.0.0)이다.
태그는 커밋 `fd081a742b7c09a7ace53bb445ce1380c533c19e`를 가리키며, 자산
`baton-cal-contracts-1.0.0.zip`의 SHA-256은
`b1aea8fed42c7b3f38320e1e0d883bd99c4d78e09d5b1dbddd4c90b2154146a7`이다.
릴리스와 자산 증명 검증을 통과했고 BATON이 이 버전과 해시를 고정해 계약 테스트를
완료했다. 이 자산에는 루트 `LICENSE`가 없으므로 계약 의미를 유지한 `1.0.1` 호환 보완판으로
재포장해 BATON이 사용하는 버전과 해시를 갱신할 예정이다. 사전 릴리스 이력과 다음 버전 규칙은
[계약 릴리스 현황](docs/contract-release-history.md), 실제 게시 명령은
[계약 릴리스 절차](docs/contract-release-procedure.md)에 정리한다.

불변 사전 릴리스 [`1.1.0-rc.1`](https://github.com/ljkhyeong/baton-cal/releases/tag/contracts-v1.1.0-rc.1)은 기존 128 KiB 문서 상한에 JSON 구조 제한을 추가하고 `prod` 데이터베이스가
로컬 기본값을 상속하지 않게 한다. 또한 응답 Date를 넘지 않는 Last-Modified와 강한 ETag 우선 판정,
취소 후 재활성화,
예상 밖 `500`의 비밀값 비노출, 데이터베이스 제한 시간의 `503 SERVICE_BUSY`와 iCal4j 4.3.0의
시간대 입력·출력 규칙 일치 검증, 복구 모드의 발급 차단, 내부 상태 조회, 시즌 표시 이름과 전체 복구 완료 계약을
포함한다. BATON이 게시 자산과 요청 스키마를 고정해 실제 컨테이너 교차 서비스 검증을 완료했다.
정식 `1.1.0` 승격 전까지 현재 운영 안정 기준은 계속 `1.0.0`이다.

현재 작업 후보 `1.1.0-rc.2`는 ID 지정 구독 생성, 복구 실행·시즌 진단 조회와 고정된 복원 매니페스트
예시를 추가했다.
아직 게시하거나 BATON의 사용 버전으로 지정하지 않았다. 기존 `rc.1` 자산은 변경하지 않는다.

## OCI 이미지 검증

운영 전달 단위는 Spring Boot의 `bootBuildImage`가 Cloud Native Buildpacks로 만드는 OCI 이미지다.
Spring Boot가 프로젝트의 Java 25 대상 버전을 기본 builder에 전달하고 프로젝트명·버전으로 이미지
이름을 정하므로 같은 값을 별도 Gradle 설정으로 반복하지 않는다. 로컬 검증 이미지는 다음처럼 만든다.

```shell
./gradlew --no-daemon bootBuildImage --imageName=baton-cal:smoke
```

만든 이미지는 실제 `prod` 프로필과 격리된 PostgreSQL에서 스모크와 대표 복원 훈련을 실행한다.

```shell
./scripts/smoke-oci-image.sh baton-cal:smoke
```

스모크의 HTTP 요청은 curl 옵션으로 연결 대기를 2초, 연결을 포함한 전체 요청을 60초로 제한한다.
readiness 반복 확인은 연결 1초·전체 요청 2초 제한을 사용한다. 구독 생성·회전 POST는
응답을 받지 못해도 서버에서 처리됐을 수 있으므로 자동 재시도하지 않는다.

스모크는 이미지의 Java 25와 비루트 실행, Flyway V1~V8 적용, 분리된 관리 포트의 DB 포함 준비 상태와
Prometheus 메트릭, 35초 유예 안의 SIGTERM 정상 종료와 SIGKILL·OOM 미발생을 확인한다. 세대 A를 유지한 채
애플리케이션 컨테이너를 실제로 재생성해
기존 공개 피드가 계속 `200`인지 확인한다. 이어 세대 A에서 만든 일정과 구독을 `pg_dump -Fc`로 백업하고
아카이브를 확인한 뒤, 애플리케이션을 중지한 상태에서 세대 B로 먼저 바꿔
`pg_restore --clean --create --exit-on-error`로 복원한다. 복원된 기존 토큰의 본문 없는 일반 `404`,
복구 모드의 생성·회전 `503` 차단, 대표 계약 픽스처의 최신 변경·취소·시즌 이름 재전달, 재전달 뒤에도 발급
차단 유지, 고정 매니페스트의 `VERIFIED`·`COMPLETED`와 완료 재시도 시 최초 시각 보존을 확인한다.
취소·최신 이름이 빠지면 검증을 거부하고, `COMPLETED`를 확인한 뒤에만 같은 세대에서 모드를
해제·재시작한다. 이어 기존 구독 rotate, 새 토큰의 `200`과
`STATUS:CANCELLED`·`SEQUENCE:3`과 최신 이름까지 검증한다. 전용 Compose 프로젝트·네트워크·볼륨과 임시 백업만
정리하고 입력 이미지는 남긴다. 별도 Dockerfile과 JRE 조립은 buildpack으로 실행 계약을 충족할 수
없을 때만 검토한다.

이 훈련은 대표 픽스처로 백업 복원 후 기존 구독 주소의 무효화와 복구 순서를 검증한다. 실제 BATON 전체
데이터를 사용한 매니페스트 검증, 운영 RTO/RPO, 백업 저장소와 암호화, 비밀 관리 시스템 연동,
실제 배포 환경의 복원 훈련을 대신하지 않는다.

GitHub Actions도 `main` 푸시와 모든 풀 리퀘스트에서 Java 25로 테스트, 계약 팩과 OCI 이미지를
만들고 같은 컨테이너 스모크·대표 복원 훈련을 실행한다. 풀 리퀘스트에서는 게시하지 않고,
`main` 푸시에서는 검증한 동일 이미지를
`ghcr.io/ljkhyeong/baton-cal:{전체 Git 커밋 SHA}`로 게시한다. 가변 `latest` 태그는 만들지 않으며
배포는 전체 SHA 태그 또는 레지스트리가 반환한 digest를 고정한다. 이 게시 자체는 계약 팩의 불변
릴리스나 공개 배포 완료를 뜻하지 않는다.

워크플로의 기본 권한은 저장소 읽기이며 패키지 쓰기는 `main` 게시 작업에만 부여한다. 외부 액션은
전체 커밋 SHA로 고정하고, Gradle은 `gradle.lockfile`과 `gradle/verification-metadata.xml`을 함께
사용해 버전과 의존성 파일의 SHA-256을 검증한다. Gradle, GitHub Actions와 Docker Compose의 새
버전은 Dependabot이 매주 풀 리퀘스트로 제안하며, 특히 iCal4j 갱신은 골든 바이트와 ETag를 직접
검토한 뒤 반영한다.

## 라이선스

이 프로젝트는 [MIT 라이선스](LICENSE)로 배포한다. 저작권 고지와 라이선스 문구를 유지하면
소프트웨어를 사용, 복제, 수정, 병합, 게시, 배포, 재허가하거나 판매할 수 있다.


## 공개 운영 준비

공개 주소는 `cal.b4ton.com`이다. 운영 대상은 Ubuntu 홈서버의 k3s이며, 사용자 확인 기준으로 DNS·
공인 IP·포트포워딩·인증서는 준비됐고 k3s는 구축 전이다. `compose.operations.yml`은 Nginx HTTPS·
요청 제한, Prometheus·Alertmanager·인증서 점검의 로컬 통합 검증에도 사용한다. Slack·Discord는
Alertmanager의 기본 연동으로 연결한다. 실제 홈서버 배포와 알림 채널 연결은 수행하지 않았다.
서버·모니터링 중단 감지는 Healthchecks.io의 무료 점검을 선택해 연결할 수 있다. Prometheus와
Alertmanager의 정상 신호가 끊기면 외부 서비스가 알리며, 계정 등록과 실제 연결은 별도다.
[운영 연동 안내](docs/operations.md), [외부 API 검토](docs/external-api-options.md).

```shell
./scripts/smoke-operations.sh baton-cal:smoke
bash scripts/smoke-alert-channels.sh
./gradlew --no-daemon ingestionLoadTest -PloadItemCount=1000
```

운영 스모크는 앞 절에서 만든 로컬 이미지로 격리된 HTTPS 프록시의 피드 200·304, 요청 초과 429,
내부 경로 차단과 실제 장애·해제 알림 전달 및 로그·메트릭 토큰 비노출을 확인한다. CI도 같은
스모크를 실행한다. 실제 캘린더 앱의 변경·취소·시즌 이름 갱신은
[앱별 안내와 확인표](docs/calendar-subscription-guide.md)에 따라 공개 HTTPS 연결 후 확인한다.
