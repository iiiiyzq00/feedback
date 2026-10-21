#!/usr/bin/env bash
set -euo pipefail

required=(
  RELEASE_APPLICATION_ID RELEASE_VERSION_CODE RELEASE_VERSION_NAME
  SDK_SERVER_URL SDK_SERVER_PUBLIC_KEY_BASE64
  COURSE_SDK_SECRET QUESTION_BANK_SDK_SECRET WRONG_BOOK_SDK_SECRET
  AI_TUTOR_SDK_SECRET APP_STORE_SDK_SECRET UNIVERSAL_FEEDBACK_SDK_SECRET
  FEEDBACK_RELEASE_STORE_FILE FEEDBACK_RELEASE_STORE_PASSWORD
  FEEDBACK_RELEASE_KEY_ALIAS FEEDBACK_RELEASE_KEY_PASSWORD
)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "missing_required_environment=$name" >&2
    exit 2
  fi
done

if [[ "$RELEASE_APPLICATION_ID" == "com.feedback.demo" ]]; then
  echo "release_application_id_must_be_formally_approved" >&2
  exit 2
fi
if [[ "$SDK_SERVER_URL" != https://* ]]; then
  echo "release_server_url_must_use_https" >&2
  exit 2
fi
if [[ ! -f "$FEEDBACK_RELEASE_STORE_FILE" ]]; then
  echo "release_keystore_not_found" >&2
  exit 2
fi
command -v apksigner >/dev/null 2>&1 || { echo "apksigner_not_found" >&2; exit 2; }
if ! [[ "$RELEASE_VERSION_CODE" =~ ^[1-9][0-9]*$ ]]; then
  echo "release_version_code_must_be_positive_integer" >&2
  exit 2
fi
if [[ ${#SDK_SERVER_PUBLIC_KEY_BASE64} -lt 128 ]]; then
  echo "release_public_key_is_implausibly_short" >&2
  exit 2
fi

for key in COURSE_SDK_SECRET QUESTION_BANK_SDK_SECRET WRONG_BOOK_SDK_SECRET AI_TUTOR_SDK_SECRET APP_STORE_SDK_SECRET UNIVERSAL_FEEDBACK_SDK_SECRET; do
  export "ORG_GRADLE_PROJECT_${key}=${!key}"
done

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"
./gradlew :demo:clean :demo:assembleRelease \
  -PRELEASE_APPLICATION_ID="$RELEASE_APPLICATION_ID" \
  -PRELEASE_VERSION_CODE="$RELEASE_VERSION_CODE" \
  -PRELEASE_VERSION_NAME="$RELEASE_VERSION_NAME" \
  -PSDK_SERVER_URL="$SDK_SERVER_URL" \
  -PSDK_SERVER_PUBLIC_KEY_BASE64="$SDK_SERVER_PUBLIC_KEY_BASE64" \
  --no-daemon

apk="demo/build/outputs/apk/release/demo-release.apk"
apksigner verify --verbose --print-certs "$apk" >/dev/null
mkdir -p dist
target="dist/universal-feedback-host-${RELEASE_VERSION_NAME}.apk"
cp "$apk" "$target"
apk_sha256="$(shasum -a 256 "$target" | awk '{print $1}')"
certificate_sha256="$(apksigner verify --print-certs "$target" | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print $2; exit}')"
[[ -n "$certificate_sha256" ]] || { echo "release_certificate_digest_missing" >&2; exit 2; }
manifest="dist/universal-feedback-host-${RELEASE_VERSION_NAME}.manifest"
printf '%s\n' \
  "artifact=$(basename "$target")" \
  "application_id=$RELEASE_APPLICATION_ID" \
  "version_code=$RELEASE_VERSION_CODE" \
  "version_name=$RELEASE_VERSION_NAME" \
  "server_origin=${SDK_SERVER_URL%%/api/*}" \
  "apk_sha256=$apk_sha256" \
  "signing_certificate_sha256=$certificate_sha256" \
  "built_at_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')" > "$manifest"
printf '%s  %s\n' "$apk_sha256" "$target"
echo "release_apk=$project_dir/$target"
echo "release_manifest=$project_dir/$manifest"
