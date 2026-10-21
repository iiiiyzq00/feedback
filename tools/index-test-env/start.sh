#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE_DIR="${PROJECT_DIR}/build/index-test-env"
ES_HOME="${D9_ES_HOME:-/Users/yang/tools/elasticsearch-8.17.0}"
MILVUS_VENV="${PROJECT_DIR}/tools/milvus-lite/.venv"
ES_PORT="${D9_ES_PORT:-19200}"
MILVUS_PORT="${D9_MILVUS_PORT:-19531}"
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/feedback-d9-index.XXXXXX")"
mkdir -p "${STATE_DIR}" "${RUN_DIR}/es-config" "${RUN_DIR}/es-data" "${RUN_DIR}/es-logs"
test -x "${ES_HOME}/bin/elasticsearch"
test -x "${MILVUS_VENV}/bin/python"

cp "${ES_HOME}/config/jvm.options" "${ES_HOME}/config/log4j2.properties" "${RUN_DIR}/es-config/"
mkdir -p "${RUN_DIR}/es-config/jvm.options.d"
printf '%s\n' '-Xms512m' '-Xmx512m' > "${RUN_DIR}/es-config/jvm.options.d/d9.options"
cat > "${RUN_DIR}/es-config/elasticsearch.yml" <<EOF
cluster.name: feedback-d9-isolated
node.name: d9-index-node
path.data: ${RUN_DIR}/es-data
path.logs: ${RUN_DIR}/es-logs
network.host: 127.0.0.1
http.port: ${ES_PORT}
transport.port: 19300
discovery.type: single-node
xpack.security.enabled: false
xpack.security.enrollment.enabled: false
EOF
ES_PATH_CONF="${RUN_DIR}/es-config" nohup "${ES_HOME}/bin/elasticsearch" >"${RUN_DIR}/elasticsearch.log" 2>&1 &
ES_PID=$!
nohup "${MILVUS_VENV}/bin/python" "${PROJECT_DIR}/tools/milvus-lite/server.py" \
  --db "${RUN_DIR}/milvus-test.db" --address "127.0.0.1:${MILVUS_PORT}" >"${RUN_DIR}/milvus.log" 2>&1 &
MILVUS_PID=$!

cleanup_failed() { kill "${ES_PID}" "${MILVUS_PID}" 2>/dev/null || true; }
trap cleanup_failed ERR
for _ in {1..180}; do curl -fsS "http://127.0.0.1:${ES_PORT}" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "http://127.0.0.1:${ES_PORT}" >/dev/null
for _ in {1..100}; do nc -z 127.0.0.1 "${MILVUS_PORT}" >/dev/null 2>&1 && break; sleep 0.2; done
nc -z 127.0.0.1 "${MILVUS_PORT}"

INDEX_NAME="feedback_d9_$(date +%s)"
COLLECTION_NAME="feedback_d9_$(date +%s)"
cat > "${STATE_DIR}/env" <<EOF
D9_INDEX_RUN_DIR=${RUN_DIR}
D9_ES_PID=${ES_PID}
D9_MILVUS_PID=${MILVUS_PID}
D9_ELASTICSEARCH_URL=http://127.0.0.1:${ES_PORT}
D9_ELASTICSEARCH_INDEX=${INDEX_NAME}
D9_MILVUS_HOST=127.0.0.1
D9_MILVUS_PORT=${MILVUS_PORT}
D9_MILVUS_COLLECTION=${COLLECTION_NAME}
EOF
trap - ERR
echo "Isolated ES ${ES_PID} and Milvus ${MILVUS_PID} are ready; env=${STATE_DIR}/env"
