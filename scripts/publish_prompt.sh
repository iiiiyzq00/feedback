#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 6 ]]; then
  echo "usage: $0 <base-url> <creator-jwt> <approver-jwt> <skill-code> <version> <evaluation-json>" >&2
  exit 2
fi
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

base_url="${1%/}"
creator_jwt="$2"
approver_jwt="$3"
skill_code="$4"
version="$5"
evaluation_file="$6"

create_response=$(./scripts/update_prompt.sh "$base_url" "$creator_jwt" "$skill_code" "$version")
prompt_id=$(jq -er '.data.id' <<<"$create_response")

curl --fail-with-body -sS -X POST "$base_url/api/v1/admin/ai-assets/prompts/$prompt_id/test" \
  -H "Authorization: Bearer $creator_jwt" -H 'Content-Type: application/json' \
  --data '[{"name":"manifest-contract","variables":{}}]' >/dev/null

evaluation_response=$(curl --fail-with-body -sS -X POST "$base_url/api/v1/admin/ai-assets/prompts/$prompt_id/evaluate" \
  -H "Authorization: Bearer $creator_jwt" -H 'Content-Type: application/json' --data-binary "@$evaluation_file")
jq -e '.data.passed == true' <<<"$evaluation_response" >/dev/null

curl --fail-with-body -sS -X POST "$base_url/api/v1/admin/ai-assets/assets/$prompt_id/approve" \
  -H "Authorization: Bearer $approver_jwt" >/dev/null
curl --fail-with-body -sS -X POST "$base_url/api/v1/admin/ai-assets/prompts/$prompt_id/publish" \
  -H "Authorization: Bearer $creator_jwt"
