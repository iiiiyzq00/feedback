#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="${MILVUS_LITE_RUN_DIR:-${SCRIPT_DIR}/run}"
PID_FILE="${RUN_DIR}/milvus-lite.pid"

if [[ ! -f "${PID_FILE}" ]]; then
  echo "Milvus Lite is already stopped."
  exit 0
fi
SERVER_PID="$(<"${PID_FILE}")"
if kill -0 "${SERVER_PID}" 2>/dev/null; then
  kill -TERM "${SERVER_PID}"
  for _ in {1..50}; do
    kill -0 "${SERVER_PID}" 2>/dev/null || break
    sleep 0.1
  done
fi
rm -f "${PID_FILE}"
echo "Milvus Lite is stopped."
