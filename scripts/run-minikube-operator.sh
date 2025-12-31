#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

./gradlew :operator:bootJar

# Use minikube's Docker daemon so the image is available inside the cluster
if command -v minikube >/dev/null 2>&1; then
  eval "$(minikube docker-env)"
else
  echo "minikube not found in PATH"
  exit 1
fi

IMAGE_TAG="local-$(date +%s)"
IMAGE_NAME="mysql-operator-poc:${IMAGE_TAG}"
docker build -t "${IMAGE_NAME}" .

kubectl apply -f k8s/mysql-operator.yaml
kubectl set image deployment/mysql-operator mysql-operator="${IMAGE_NAME}" --record

echo "Operator deployed with image ${IMAGE_NAME}. Check status with: kubectl get pods -l app=mysql-operator"
