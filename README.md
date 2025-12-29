# MySQL Operator PoC

Kubernetes에서 MySQL 인스턴스를 선언적으로 운영하기 위한 Operator PoC입니다.
`MySQLInstance` 커스텀 리소스를 적용하면 MySQL 관련 리소스가 자동 생성/동기화됩니다.

## 빠른 개요
- **핵심 기능**: MySQL 인스턴스 생성, 자동 튜닝(my.cnf), Reset/Restart 액션, Clone/Schema Clone
- **기술 스택**: Kotlin, Spring Boot, Java Operator SDK, Fabric8 Client
- **배포 대상**: Kubernetes (minikube 포함)

## 문서
- 상세 소개: `docs/PROJECT_OVERVIEW.md`
- 운영 가이드: `docs/OPERATIONS_GUIDE.md`
- CRD 스펙 레퍼런스: `docs/CRD_REFERENCE.md`
- 테스트 결과: `docs/POC_TEST_REPORT.md`
- 트러블슈팅: `docs/OPERATOR_TROUBLESHOOTING.md`

## 빠른 시작
1) 빌드
```
./gradlew bootJar
```

2) 이미지 빌드
```
docker build -t mysql-operator-poc:local .
```

3) 배포
```
kubectl apply -f k8s/mysql-operator.yaml
```

4) 샘플 CR 적용
```
kubectl apply -f mysqlinstance-sample.yaml
```

## minikube에서 실행
```
scripts/run-minikube-operator.sh
```

## 개발/테스트
E2E 테스트는 minikube에서 Operator를 실행한 뒤, 환경변수로 활성화합니다.
```
RUN_E2E=true TEST_NAMESPACE=default ./gradlew test --rerun-tasks --tests "com.testcraft.mysqloperatorpoc.MySQLInstanceE2ETests.idempotencyCreatesSingleResources"
```
# mysql-operator-test
