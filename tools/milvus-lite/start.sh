#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="${MILVUS_LITE_VENV_DIR:-${SCRIPT_DIR}/.venv}"
RUN_DIR="${MILVUS_LITE_RUN_DIR:-${SCRIPT_DIR}/run}"
DB_FILE="${MILVUS_LITE_DB_FILE:-${SCRIPT_DIR}/data/feedback.db}"
ADDRESS="${MILVUS_LITE_ADDRESS:-127.0.0.1:19530}"
PID_FILE="${RUN_DIR}/milvus-lite.pid"
LOG_FILE="${RUN_DIR}/milvus-lite.log"

if [[ ! -x "${VENV_DIR}/bin/python" ]]; then
  echo "Milvus Lite is not installed. Run ${SCRIPT_DIR}/setup.sh first." >&2
  exit 1
fi
mkdir -p "${RUN_DIR}" "$(dirname "${DB_FILE}")"
if [[ -f "${PID_FILE}" ]] && kill -0 "$(<"${PID_FILE}")" 2>/dev/null; then
  echo "Milvus Lite is already running (PID $(<"${PID_FILE}"))."
  exit 0
fi
rm -f "${PID_FILE}"
nohup "${VENV_DIR}/bin/python" "${SCRIPT_DIR}/server.py" --db "${DB_FILE}" --address "${ADDRESS}" >"${LOG_FILE}" 2>&1 &
SERVER_PID=$!
echo "${SERVER_PID}" >"${PID_FILE}"

for _ in {1..100}; do
  if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
    cat "${LOG_FILE}" >&2
    rm -f "${PID_FILE}"
    exit 1
  fi
  if "${VENV_DIR}/bin/python" - "${ADDRESS}" 2>/dev/null <<'PY'
import socket, sys
host, port = sys.argv[1].rsplit(":", 1)
with socket.create_connection((host, int(port)), timeout=0.2):
    pass
PY
  then
    echo "Milvus Lite is ready at ${ADDRESS} (PID ${SERVER_PID}, DB ${DB_FILE})."
    exit 0
  fi
  sleep 0.1
done
echo "Milvus Lite did not become ready; see ${LOG_FILE}." >&2
exit 1
