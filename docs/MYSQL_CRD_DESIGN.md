# MySQL CRD 설계 문서

## 1. 목적과 범위
이 문서는 `MySQLInstance` CRD의 **설계 목표, 스키마, 동작 규칙, 운영 고려사항**을 정의합니다.  
현재 구현 범위는 PoC이며, 프로덕션 대응을 위한 확장 포인트를 함께 명시합니다.

## 2. 설계 목표
- **선언형 운영**: 사용자가 원하는 상태를 CR로 표현하면 Operator가 리소스를 수렴
- **단순한 UX**: 최소 입력으로 MySQL 인스턴스 생성 가능
- **확장성**: Clone/Reset/Restart 등 운영 액션을 확장 가능한 구조로 제공
- **가시성**: status로 현재 상태, 튜닝, 작업 이력 관측 가능
- **안전성**: 기본값 제공, 위험 작업은 명시적 트리거 필요

## 3. 비목표(Non-Goals)
- HA/멀티 레플리카 지원
- 자동 스케일링 및 MySQL 샤딩
- 자동 백업/복구 파이프라인
- 실데이터 Reset 로직(현재는 상태 기록만 수행)

## 4. CRD 개요
- **apiVersion**: `testcraft.com/v1`
- **kind**: `MySQLInstance`
- **scope**: Namespaced
- **주요 리소스**: StatefulSet, Service(Headless), ConfigMap, Secret, PVC, Job(선택)

## 5. 스키마 설계
### 5.1 spec 구조
- `image`: MySQL 이미지 정의
- `port`: MySQL 포트
- `database`: 초기 생성 DB 이름
- `rootPassword`: root 비밀번호(Secret 저장)
- `resources`: CPU/메모리 설정
- `storage`: PVC 설정
- `mysqlConfig`: my.cnf 커스텀 설정
- `initStrategy`: 초기화 전략
- `cloneSource`: Clone 소스 정보

### 5.2 status 구조
운영자가 상태를 쉽게 판단하도록 **요약 정보 + 작업 이력**을 제공:
- `ready`, `phase`, `message`: 상태 요약
- `serviceName`: 접속용 Service 명
- `lastAppliedConfigHash`: my.cnf 반영 해시
- `clonePhase`, `lastCloneTime`: Clone 진행 상태
- `resetPhase`, `lastResetTime`: Reset 처리 결과

## 6. 필드별 설계 의도
### 6.1 image
기본 MySQL 이미지를 제공해 최소 입력으로 실행 가능하도록 함.
사용자는 레지스트리/이미지/태그를 조합해 커스터마이징 가능.

### 6.2 resources
Kubernetes 리소스 제한을 그대로 사용하면서,
메모리 limit에 기반한 **Smart Tuning**을 지원하기 위한 진입점.

### 6.3 storage
StatefulSet + PVC 기반 영속 스토리지 구성.
`storageClassName`으로 환경별 스토리지 정책을 선택 가능.

### 6.4 mysqlConfig
사용자 정의 `my.cnf` 설정을 전달하기 위한 맵.
자동 튜닝 값과 병합되며, 동일 키는 사용자 값이 우선.

### 6.5 initStrategy / cloneSource
초기화 방식 선택을 명확하게 표현:
- `EMPTY`: 초기화 없음
- `CLONE`: 데이터 포함 전체 복제
- `SCHEMA_CLONE`: 스키마만 복제
Clone 수행 시 필요한 소스 정보는 `cloneSource`로 분리해 표현.

## 7. 동작 규칙
- `spec.resources.limits.memory`가 존재하면 Smart Tuning 적용
- `spec.mysqlConfig`는 자동 튜닝 값과 병합
- `spec.initStrategy`가 `CLONE`/`SCHEMA_CLONE`이고 `cloneSource`가 있을 때 Clone Job 생성
- `action.mysql.sandbox/restart`는 StatefulSet 롤링 재시작을 유도
- `action.mysql.sandbox/reset`는 PoC 범위에서 상태 기록만 수행

## 8. 기본값과 합리적 선택
| 필드 | 기본값 | 의도 |
| --- | --- | --- |
| `image.registry` | `docker.io` | 범용 레지스트리 |
| `image.imageName` | `library/mysql` | 표준 이미지 |
| `image.tag` | `8.0` | 안정적 버전 |
| `port` | `3306` | 기본 포트 |
| `database` | `testcraft` | 샘플 DB |
| `rootPassword` | `password` | PoC 기본값 |
| `storage.size` | `1Gi` | PoC 범위 최소 용량 |
| `initStrategy` | `EMPTY` | 기본 동작 단순화 |

## 9. 유효성/검증 설계
현재 PoC에서는 별도의 Validation Webhook을 적용하지 않음.
향후 확장 시 다음 검증을 추가 권장:
- `rootPassword` 최소 길이/복잡도
- `storage.size` 형식 검증
- `initStrategy`와 `cloneSource`의 정합성
- `resources.limits.memory` 파싱 가능 여부

## 10. 보안 고려사항
- Secret에 root 비밀번호 저장, RBAC 제한 필요
- Clone Job에 소스 DB 비밀번호 전달(환경변수), Secret 분리 필요
- CRD 권한은 최소 범위로 제한 권장

## 11. 운영 고려사항
- ConfigMap 변경 시 자동 재시작 없음
- PVC 삭제 정책은 스토리지 프로비저너 정책에 영향
- 단일 replica만 지원(HA 미지원)

## 12. 확장 방향
### 12.1 기능 확장
- 실제 Reset 로직(테이블 truncate, DB drop 등)
- 백업/복구 API
- 자동 재시작(설정 변경 감지)

### 12.2 API 확장
- `backupPolicy`, `restoreSource` 추가
- `monitoring` 설정(메트릭, 로그 수집)
- `auth` 설정(root 외 사용자 관리)

## 13. 관련 문서
- 상세 소개: `docs/PROJECT_OVERVIEW.md`
- 운영 가이드: `docs/OPERATIONS_GUIDE.md`
- CRD 레퍼런스: `docs/CRD_REFERENCE.md`
