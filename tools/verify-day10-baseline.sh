#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

required_files=(
  "settings.gradle.kts"
  "build.gradle.kts"
  "backend/build.gradle.kts"
  "sdk/build.gradle.kts"
  "demo/build.gradle.kts"
  "frontend/package.json"
  "frontend/package-lock.json"
)

for required_file in "${required_files[@]}"; do
  if [[ ! -f "$required_file" ]]; then
    echo "BASELINE_ERROR missing=$required_file" >&2
    exit 1
  fi
done

latest_migration="$(find backend/src/main/resources/db/migration -maxdepth 1 -type f -name 'V*__*.sql' -print | sort -V | tail -n 1)"
migration_count="$(find backend/src/main/resources/db/migration -maxdepth 1 -type f -name 'V*__*.sql' | wc -l | tr -d ' ')"

echo "D10_BASELINE project=$project_dir"
echo "D10_BASELINE captured_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo "D10_BASELINE git_metadata=$([[ -d .git ]] && echo present || echo absent)"
echo "D10_BASELINE latest_migration=$latest_migration migration_files=$migration_count"
echo "D10_BASELINE java=$(java -version 2>&1 | head -n 1)"
echo "D10_BASELINE gradle=$(./gradlew --version | awk '/^Gradle / {print $2; exit}')"
echo "D10_BASELINE node=$(node --version) npm=$(npm --version)"
command -v mysql >/dev/null && echo "D10_BASELINE mysql=$(mysql --version)"
command -v redis-server >/dev/null && echo "D10_BASELINE redis=$(redis-server --version)"

echo "D10_BASELINE_CHECKSUMS_BEGIN"
find \
  settings.gradle.kts build.gradle.kts gradle.properties gradle/wrapper \
  backend/src backend/build.gradle.kts \
  sdk/src sdk/build.gradle.kts \
  demo/src demo/build.gradle.kts \
  frontend/src frontend/package.json frontend/package-lock.json \
  -type f \
  ! -name '.DS_Store' \
  -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256
echo "D10_BASELINE_CHECKSUMS_END"

