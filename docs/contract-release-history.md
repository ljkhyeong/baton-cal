# BATON CAL 계약 릴리스 현황

이 문서는 BATON이 고정할 계약 자산의 현재 게시 상태와 이력을 관리한다. 계약 의미의 기준은
[PRD-0002](PRD/0002_mvp-contract/spec.md), 기계 판독형 형식은
[계약 팩](../contracts/README.md), 다음 개발 작업은 [HANDOFF](../HANDOFF.md)가 맡는다.

## 현재 생산자 기준

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

## 다음 검토 후보

| 항목 | 값 |
| --- | --- |
| 버전 | `1.1.0-rc.1` |
| 예정 태그 | `contracts-v1.1.0-rc.1` |
| 자산 | `baton-cal-contracts-1.1.0-rc.1.zip` |
| 변경 의미 | JSON 구조 제한, `prod` 데이터베이스 기본값 차단, 표현 변경 검증 값 전진, 재활성화·500 비노출 회귀 검증 |
| 상태 | 로컬 전체 테스트·실행 JAR·계약 ZIP 검증 완료, 미게시, BATON 미고정 |

이 후보가 게시되고 BATON 교차 서비스 테스트를 통과하기 전까지 생산자 기준은 `1.0.0`이다.

## 게시 이력

| 버전 | 태그 커밋 | 자산 SHA-256 | 결과 |
| --- | --- | --- | --- |
| `1.0.0-rc.1` | `ce613f2ed72aa7ada61592664ca8feb8077eac75` | `0ca23e9e5189d41383d21c334005870446aa52a80e7bcb23e9183d8869acc546` | 단일 시점과 종일 날짜 형태가 없어 이력으로 보존 |
| `1.0.0-rc.2` | `730ae49a8b8eccf10e8f84f93b8a6a9d0fd24549` | `75120a7d21b6ea78c1e8bdab60829899525c1607262119053ea5904b57bd1eaf` | 세 시간 형태와 실제 BATON 생산자 연동 검증 완료 |
| `1.0.0` | `fd081a742b7c09a7ace53bb445ce1380c533c19e` | `b1aea8fed42c7b3f38320e1e0d883bd99c4d78e09d5b1dbddd4c90b2154146a7` | 계약 의미 변경 없이 안정 버전으로 승격하고 BATON 고정 완료 |

게시된 사전 릴리스와 안정 릴리스는 교체하거나 같은 태그로 다시 만들지 않는다.

## 문서와 버전 관리 원칙

- `contracts/VERSION`이 계약 버전과 ZIP 파일명, 릴리스 태그의 단일 원천이다.
- `contracts/**`와 PRD-0002는 계약 ZIP에 포함된다. 이 파일을 바꾸면 문구만 고쳐도 새 계약
  바이트가 되므로 버전을 올리고 새 자산과 태그를 게시한다.
- 이미 게시된 ZIP 안의 README와 PRD는 해당 릴리스가 만들어진 시점의 스냅샷이다. 게시 뒤의 현재
  고정 상태와 검증 결과는 이 문서에서 갱신한다.
- CI의 `upload-artifact` 자산은 변경 검토용 임시 파일이다. BATON은 GitHub의 불변 릴리스 자산과
  SHA-256을 고정한다.
- 계약을 바꾸지 않는 애플리케이션·운영 문서 변경은 계약 버전을 올리지 않는다.
