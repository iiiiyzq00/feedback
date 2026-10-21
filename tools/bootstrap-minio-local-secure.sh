#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
keychain_account="${USER}"
alias_name="feedback-local-bootstrap"
bucket="${ATTACHMENT_OBJECT_STORAGE_BUCKET:-feedback-private}"

secret() {
  security find-generic-password -a "$keychain_account" -s "feedback-platform-$1" -w
}

root_user="$(secret MINIO_ROOT_USER)"
root_password="$(secret MINIO_ROOT_PASSWORD)"
app_user="$(secret ATTACHMENT_OBJECT_STORAGE_ACCESS_KEY)"
app_password="$(secret ATTACHMENT_OBJECT_STORAGE_SECRET_KEY)"
mc="$project_dir/.runtime/minio/bin/mc"

"$mc" alias set "$alias_name" "http://127.0.0.1:${MINIO_API_PORT:-19000}" "$root_user" "$root_password" >/dev/null
"$mc" mb --ignore-existing "$alias_name/$bucket"
"$mc" version enable "$alias_name/$bucket"
"$mc" admin user add "$alias_name" "$app_user" "$app_password"
"$mc" admin policy create "$alias_name" feedback-attachments "$project_dir/tools/minio-attachment-policy.json"
"$mc" admin policy attach "$alias_name" feedback-attachments --user "$app_user"
"$mc" anonymous set none "$alias_name/$bucket"
"$mc" alias remove "$alias_name" >/dev/null

printf 'Local MinIO bucket initialized: %s (private, versioning enabled)\n' "$bucket"
