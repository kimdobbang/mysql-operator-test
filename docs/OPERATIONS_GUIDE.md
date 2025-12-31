# MySQL Operator PoC 운영 가이드

## 1. 목적과 범위
이 문서는 PoC Operator를 운영 환경 또는 테스트 클러스터에서 **설치, 운영, 업그레이드, 문제 해결**하는 데 필요한 정보를 제공합니다.
PoC 범위이므로 일부 기능(예: Reset 동작)은 상태 기록만 수행합니다.

## 2. 사전 요구사항
- Kubernetes 클러스터 (minikube 포함)
- `kubectl`
- Docker
- Java 21 (빌드 시)

## 3. 설치 및 배포
### 3.1 빌드
```
./gradlew bootJar
```

### 3.2 이미지 빌드
```
docker build -t mysql-operator-poc:local .
```

### 3.3 배포
```
kubectl apply -f k8s/mysql-operator.yaml
```

### 3.4 CR 적용
```
kubectl apply -f mysqlinstance-sample.yaml
```

## 4. 구성 및 운영 포인트
### 4.1 리소스 생성 정책
- **StatefulSet**: 1 replica, Headless Service 사용
- **ConfigMap**: `my.cnf` 생성, 체크섬 주석 포함
- **Secret**: `rootPassword` 보관
- **PVC**: `storage.size` 기준으로 생성

### 4.2 Config 변경
- ConfigMap 변경 시 **자동 재시작 로직은 없음**
- 운영에서는 변경 후 수동 롤링 재시작 또는 별도 정책 적용 필요

### 4.3 Reset/Restart 액션
#### Restart
```
kubectl annotate mysqlinstance <name> action.mysql.sandbox/restart=true --overwrite
```
StatefulSet PodTemplate에 `mysql.sandbox/restartedAt`이 기록되며 롤링 재시작을 유도합니다.

#### Reset
```
kubectl annotate mysqlinstance <name> action.mysql.sandbox/reset=truncate --overwrite
```
PoC 범위에서는 실제 데이터 삭제 없이 상태에 결과만 기록합니다.

### 4.4 Clone/Schema Clone
`spec.initStrategy`가 `CLONE` 또는 `SCHEMA_CLONE`일 때 Clone Job이 생성됩니다.
```
kubectl get job -n <namespace> | rg <name>-clone
```

## 5. 업그레이드 전략
### 5.1 이미지 태그 기반 롤링
```
kubectl set image deployment/mysql-operator mysql-operator=<new-image> --record
```

### 5.2 minikube 스크립트 사용
`scripts/run-minikube-operator.sh`는 timestamp 기반 태그를 사용하여
이미지 갱신이 확실히 반영되도록 합니다.

## 6. 관찰 및 모니터링
### 6.1 Operator 상태 확인
```
kubectl get pods -l app=mysql-operator
```

### 6.2 MySQLInstance 상태 확인
```
kubectl get mysqlinstance <name> -n <namespace> -o yaml
```

### 6.3 기본 진단 명령
```
kubectl get sts <name> -n <namespace>
kubectl get svc <name> -n <namespace>
kubectl get cm <name> -n <namespace>
kubectl get secret <name>-mysql-secret -n <namespace>
kubectl get pvc -n <namespace>
```

## 7. 장애/문제 해결
문제 발생 시 `docs/OPERATOR_TROUBLESHOOTING.md`를 우선 확인합니다.
아래는 요약 포인트입니다.

### 7.1 Restart/Reset 동작 안됨
- metadata-only 변경은 이벤트가 전달되지 않을 수 있으므로
  주기적 리컨실(`@MaxReconciliationInterval`)이 동작하는지 확인

### 7.2 Patch 충돌/거부
- 409 Conflict: resource/status 패치를 분리했을 때 발생 가능
- `managedFields`가 포함되면 400 오류 발생 가능

### 7.3 PVC 삭제 지연
- 스토리지 프로비저너 정책에 따라 PVC finalizer가 남을 수 있음

## 8. 보안/운영 고려사항
- Clone Job은 소스 DB 비밀번호를 Job 환경변수로 전달합니다.
  운영 환경에서는 Secret 분리 및 암호화 정책 필요
- `rootPassword`는 Secret에 저장되며, 권한 제어 필요
- RBAC 권한을 최소화하려면 `k8s/mysql-operator.yaml`를 기준으로 조정

## 9. 운영 범위 요약
- **지원**: 단일 replica, 자동 튜닝, 재시작/리셋/클론
- **미지원**: HA 구성, 자동 ConfigMap 반영 재시작, 실제 Reset 로직

## 10. API 문서
- `docs/API_SPEC.md`
