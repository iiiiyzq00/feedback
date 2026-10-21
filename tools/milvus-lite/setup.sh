#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="${MILVUS_LITE_VENV_DIR:-${SCRIPT_DIR}/.venv}"
PYTHON_BIN="${MILVUS_LITE_PYTHON:-python3}"

"${PYTHON_BIN}" -m venv "${VENV_DIR}"
"${VENV_DIR}/bin/python" -m pip install --upgrade pip
"${VENV_DIR}/bin/python" -m pip install -r "${SCRIPT_DIR}/requirements.txt"
"${VENV_DIR}/bin/python" -c 'import importlib.metadata; print("milvus-lite", importlib.metadata.version("milvus-lite"))'
