# HANDOFF

진행 중 작업 없음.

## 현재 구현

- PRD-0002와 ADR-0002가 season-only MVP 계약과 기술 스택의 기준이다.
- schedule snapshot ingest, duplicate/stale/conflict 분류와 원자적 projection rebuild가 구현되어 있다.
- digest-only subscription create/rotate/revoke와 public conditional `.ics` GET이 구현되어 있다.
- iCal4j model/serializer, PostgreSQL/Flyway와 Testcontainers integration test를 사용한다.
- strict timestamp shape와 microsecond canonicalization, Java/iCal4j timezone 교집합을 검증한다.
- empty/DST/midnight/cancellation golden, transaction rollback/retry와 subscription CAS 경쟁을 검증한다.
- credential 응답은 `no-store`이며 health/readiness와 로컬 PostgreSQL 실행 절차가 준비되어 있다.

## 검증

- `./gradlew --no-daemon test`: 37개 테스트 성공 (2026-08-13)
- `./gradlew --no-daemon bootJar`: executable jar 생성 성공 (2026-08-13)

## 다음 작업

1. BATON producer가 PRD-0002 JSON/revision/cancellation 계약을 지키는 consumer-driven fixture를 연결한다.
2. internal bearer 발급·회전, TLS, reverse-proxy token path redaction과 request size limit을 정한다.
3. 추가 Unicode escaping/folding edge fixture와 dependency update 절차를 보강한다.
4. backup/restore와 배포 runbook을 실제 환경에서 검증한다.

## 현재 제한

- BATON producer와 아직 연결하지 않았다.
- public deployment, production secret 운영과 rate limit은 준비되지 않았다.
- 현재 구현은 MVP이며 production readiness 완료를 뜻하지 않는다.

## 저장소

- 공개 원격 저장소: <https://github.com/ljkhyeong/baton-cal>
