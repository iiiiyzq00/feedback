#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
"${PROJECT_DIR}/tools/index-test-env/start.sh"
trap '"${PROJECT_DIR}/tools/index-test-env/stop.sh"' EXIT
set -a; source "${PROJECT_DIR}/build/index-test-env/env"; set +a
"${PROJECT_DIR}/gradlew" -p "${PROJECT_DIR}" :backend:realIndexIntegrationTest \
  -Dd9.external-infra=true \
  -Dd9.elasticsearch.url="${D9_ELASTICSEARCH_URL}" -Dd9.elasticsearch.index="${D9_ELASTICSEARCH_INDEX}" \
  -Dd9.milvus.host="${D9_MILVUS_HOST}" -Dd9.milvus.port="${D9_MILVUS_PORT}" \
  -Dd9.milvus.collection="${D9_MILVUS_COLLECTION}" --rerun-tasks
