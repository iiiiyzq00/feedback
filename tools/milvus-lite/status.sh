#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="${MILVUS_LITE_RUN_DIR:-${SCRIPT_DIR}/run}"
PID_FILE="${RUN_DIR}/milvus-lite.pid"

if [[ -f "${PID_FILE}" ]] && kill -0 "$(<"${PID_FILE}")" 2>/dev/null; then
  echo "Milvus Lite is running (PID $(<"${PID_FILE}"))."
  exit 0
fi
echo "Milvus Lite is stopped."
exit 1
