#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
keychain_account="${USER}"

secret() {
  security find-generic-password -a "$keychain_account" -s "feedback-platform-$1" -w
}

export MINIO_ROOT_USER="$(secret MINIO_ROOT_USER)"
export MINIO_ROOT_PASSWORD="$(secret MINIO_ROOT_PASSWORD)"
export MINIO_DATA_DIR="${MINIO_DATA_DIR:-$project_dir/.runtime/minio/data}"

exec "$project_dir/.runtime/minio/bin/minio" server "$MINIO_DATA_DIR" \
  --address "127.0.0.1:${MINIO_API_PORT:-19000}" \
  --console-address "127.0.0.1:${MINIO_CONSOLE_PORT:-19001}"
