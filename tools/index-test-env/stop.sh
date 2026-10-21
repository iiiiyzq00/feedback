#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${PROJECT_DIR}/build/index-test-env/env"
test -f "${ENV_FILE}" || exit 0
set -a; source "${ENV_FILE}"; set +a
kill "${D9_ES_PID}" "${D9_MILVUS_PID}" 2>/dev/null || true
for _ in {1..50}; do
  kill -0 "${D9_ES_PID}" 2>/dev/null || kill -0 "${D9_MILVUS_PID}" 2>/dev/null || break
  sleep 0.1
done
rm -rf -- "${D9_INDEX_RUN_DIR}"
rm -f -- "${ENV_FILE}"
echo "Isolated index test environment stopped and removed."
