# BATON CAL 계약 릴리스 현황

이 문서는 BATON이 고정할 계약 자산의 현재 게시 상태와 이력을 관리한다. 계약 의미의 기준은
[PRD-0002](PRD/0002_mvp-contract/spec.md), 기계 판독형 형식은
[계약 팩](../contracts/README.md), 게시 명령은 [계약 릴리스 절차](contract-release-procedure.md),
다음 개발 작업은 [HANDOFF](../HANDOFF.md)가 맡는다.

## 안정 계약 기준

| 항목 | 값 |
| --- | --- |
| 버전 | `1.0.0` |
| 불변 태그 | `contracts-v1.0.0` |
| 태그 커밋 | `fd081a742b7c09a7ace53bb445ce1380c533c19e` |
| 자산 | `baton-cal-contracts-1.0.0.zip` |
| 자산 SHA-256 | `b1aea8fed42c7b3f38320e1e0d883bd99c4d78e09d5b1dbddd4c90b2154146a7` |
| 릴리스 | [BATON CAL 계약 1.0.0](https://github.com/ljkhyeong/baton-cal/releases/tag/contracts-v1.0.0) |
| BATON 연동 | 버전·태그·자산·SHA-256 고정과 생산자 계약 테스트 완료 |

릴리스 증명과 로컬 자산은 다음 표준 명령으로 검증한다.

```shell
gh release verify contracts-v1.0.0
gh release download contracts-v1.0.0 --pattern baton-cal-contracts-1.0.0.zip
gh release verify-asset contracts-v1.0.0 baton-cal-contracts-1.0.0.zip
```

## 호환 보완 예정

| 항목 | 값 |
| --- | --- |
| 버전 | `1.0.1` |
| 예정 태그 | `contracts-v1.0.1` |
| 자산 | `baton-cal-contracts-1.0.1.zip` |
| 변경 범위 | `1.0.0`의 스키마·예시·골든·PRD 의미를 유지하고 루트 MIT `LICENSE`와 새 버전 표식만 추가 |
| 상태 | 준비 절차와 릴리스 노트 작성 완료, 미게시, BATON 미고정 |

불변 `1.0.0` 자산에는 루트 `LICENSE`가 없다. 같은 태그를 교체하지 않고 과거 안정 태그에서
`1.0.1`을 별도로 재포장한다. BATON은 게시된 태그·자산·SHA-256으로 다시 고정하고 생산자 계약
테스트를 실행해야 한다.

## 게시된 검토 후보

| 항목 | 값 |
| --- | --- |
| 버전 | `1.1.0-rc.2` |
| 불변 태그 | `contracts-v1.1.0-rc.2` |
| 태그 커밋 | `3ba5889b6396749df74ac0e181da24337221fa56` |
| 자산 | `baton-cal-contracts-1.1.0-rc.2.zip` (58,641 bytes) |
| 자산 SHA-256 | `6b2feb97eae937a930e39d1cd4dbe7e156777a5e314bbce1a27600f20426c208` |
| 변경 의미 | 일정 묶음 수신, ID 지정 구독 생성, 복구 실행·시즌 진단 조회, 입력 타입 제한, HEAD 본문 조회 생략, DB 장애 재시도 안내와 상태 오류 캐시 금지 |
| 릴리스 | [BATON CAL 계약 1.1.0-rc.2](https://github.com/ljkhyeong/baton-cal/releases/tag/contracts-v1.1.0-rc.2), [릴리스 노트](releases/contracts-v1.1.0-rc.2.md) |
| BATON 적용 | `7a064bcd`에서 버전·자산 해시와 구독·복구 스키마 출처 갱신. [BATON PR #21](https://github.com/ljkhyeong/baton/pull/21) |
| 검증 | 릴리스·ZIP 증명 통과. BATON 스키마 9개와 공식 ZIP의 바이트 일치, 클라이언트 테스트 29개·실제 컨테이너 교차 서비스 테스트 5개 통과 |

2026-09-12 `gh release verify`와 `gh release verify-asset`으로 게시 증명과 자산을 검증했다.
이 후보는 생산자 검증 기준이며 정식 버전 승격 전까지 안정 계약은 `1.0.0`이다.
실제 캘린더 앱 검증·운영 활성화와 BATON의 묶음 전송 연결은 남아 있다.

## 게시 이력

BATON의 `contracts/baton-cal/candidate`는 기존 경로를 유지하지만 현재 스키마 출처는 공식 `rc.2`
자산이다. 이전 개발 소스 검증 기록은 Git 이력에 보존한다.

| 버전 | 태그 커밋 | 자산 SHA-256 | 결과 |
| --- | --- | --- | --- |
| `1.0.0-rc.1` | `ce613f2ed72aa7ada61592664ca8feb8077eac75` | `0ca23e9e5189d41383d21c334005870446aa52a80e7bcb23e9183d8869acc546` | 단일 시점과 종일 날짜 형태가 없어 이력으로 보존 |
| `1.0.0-rc.2` | `730ae49a8b8eccf10e8f84f93b8a6a9d0fd24549` | `75120a7d21b6ea78c1e8bdab60829899525c1607262119053ea5904b57bd1eaf` | 세 시간 형태와 실제 BATON 생산자 연동 검증 완료 |
| `1.0.0` | `fd081a742b7c09a7ace53bb445ce1380c533c19e` | `b1aea8fed42c7b3f38320e1e0d883bd99c4d78e09d5b1dbddd4c90b2154146a7` | 계약 의미 변경 없이 안정 버전으로 승격하고 BATON 고정 완료 |
| `1.1.0-rc.1` | `f1573edef1adf900570cd55f9bd7d7044566b6bd` | `7ac97568c8b10e4ac2dadb9d463312c1a5985c424a3a3a9e69c2bd8ee6dd376f` | 시즌 이름·복구 완료 계약을 게시하고 BATON 생산자 교차 서비스 검증 완료 |
| `1.1.0-rc.2` | `3ba5889b6396749df74ac0e181da24337221fa56` | `6b2feb97eae937a930e39d1cd4dbe7e156777a5e314bbce1a27600f20426c208` | 묶음 수신·구독·복구 진단 계약 게시, BATON 공식 자산 고정과 교차 서비스 검증 완료 |

게시된 사전 릴리스와 안정 릴리스는 교체하거나 같은 태그로 다시 만들지 않는다.

## 문서와 버전 관리 원칙

- `contracts/VERSION`이 계약 버전과 ZIP 파일명, 릴리스 태그의 단일 원천이다.
- 루트 `LICENSE`, `contracts/**`와 PRD-0002는 계약 ZIP에 포함된다. 이 파일을 바꾸면 문구만
  고쳐도 새 계약 바이트가 된다. 이미 게시한 입력과 달라지면 버전을 올리고 새 자산과 태그를 게시한다.
  게시 전 작업 후보는 같은 후보 버전에서 수정할 수 있지만 공식 자산은 깨끗한 원격 커밋에서만 만든다.
- 이미 게시된 ZIP 안의 README와 PRD는 해당 릴리스가 만들어진 시점의 스냅샷이다. 게시 뒤의 현재
  고정 상태와 검증 결과는 이 문서에서 갱신한다.
- CI의 `upload-artifact` 자산은 변경 검토용 임시 파일이다. BATON은 GitHub의 불변 릴리스 자산과
  SHA-256을 고정한다.
- 계약을 바꾸지 않는 애플리케이션·운영 문서 변경은 계약 버전을 올리지 않는다.
