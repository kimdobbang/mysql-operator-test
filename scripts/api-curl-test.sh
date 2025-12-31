#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
NAME="${NAME:-mysql-demo}"

echo "Creating MySQLInstance..."
curl -sS -X POST "${BASE_URL}/api/mysqlinstances" \
  -H 'Content-Type: application/json' \
  -d "{
    \"name\": \"${NAME}\",
    \"namespace\": \"default\"
  }"
echo

echo "Updating resources..."
curl -sS -X PUT "${BASE_URL}/api/mysqlinstances/${NAME}/resources" \
  -H 'Content-Type: application/json' \
  -d '{
    "limits": { "cpu": "1", "memory": "1Gi" },
    "requests": { "cpu": "500m", "memory": "512Mi" }
  }'
echo

echo "Restarting..."
curl -sS -X POST "${BASE_URL}/api/mysqlinstances/${NAME}/restart" \
  -H 'Content-Type: application/json' \
  -d '{}'
echo

echo "Resetting..."
curl -sS -X POST "${BASE_URL}/api/mysqlinstances/${NAME}/reset" \
  -H 'Content-Type: application/json' \
  -d '{ "action": "truncate" }'
echo

echo "Triggering clone (schema only)..."
curl -sS -X POST "${BASE_URL}/api/mysqlinstances/${NAME}/clone" \
  -H 'Content-Type: application/json' \
  -d '{
    "initStrategy": "SCHEMA_CLONE",
    "cloneSource": {
      "host": "source-mysql",
      "port": 3306,
      "username": "root",
      "password": "password",
      "database": "sample"
    }
  }'
echo

echo "Done."
