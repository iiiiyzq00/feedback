#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 4 ]]; then
  echo "usage: $0 <base-url> <jwt> <skill-code> <version> [prompt-file]" >&2
  exit 2
fi

base_url="$1"
jwt="$2"
skill_code="$3"
version="$4"
prompt_file="${5:-prompts/${skill_code}/${version}.txt}"

request_file=$(mktemp "${TMPDIR:-/tmp}/feedback-prompt-request.XXXXXX.json")
trap 'rm -f "$request_file"' EXIT
python3 - "$prompt_file" "$version" > "$request_file" <<'PY'
import json, pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
print(json.dumps({"promptVersion": sys.argv[2], "template": text,
                  "modelConfig": {"temperature": 0}, "releaseNotes": "Git prompt asset"}, ensure_ascii=False))
PY

curl --fail-with-body -sS -X POST "${base_url%/}/api/v1/admin/ai-assets/skills/${skill_code}/prompts" \
  -H "Authorization: Bearer ${jwt}" -H 'Content-Type: application/json' \
  --data-binary "@$request_file"
