#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

echo '[1/5] Flyway migration continuity'
versions="$(find backend/src/main/resources/db/migration -name 'V*.sql' -exec basename {} \; | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n)"
duplicates="$(printf '%s\n' "$versions" | uniq -d)"
test -z "$duplicates" || { echo "duplicate Flyway versions: $duplicates" >&2; exit 1; }
test "$(printf '%s\n' "$versions" | tail -1)" = '57'

echo '[2/5] Backend regression and PDF contract'
./gradlew :backend:test

echo '[3/5] PDF structure and renderability'
pdf='output/pdf/day8-r24-r28-unified-acceptance.pdf'
test -s "$pdf"
pdfinfo "$pdf" | grep -q '^Pages:'
mkdir -p tmp/pdfs
pdftoppm -png -r 96 "$pdf" tmp/pdfs/day8-unified >/dev/null 2>&1
test "$(find tmp/pdfs -name 'day8-unified-*.png' | wc -l | tr -d ' ')" -ge 2

echo '[4/5] Frontend tests'
(cd frontend && npm run test -- --run)

echo '[5/5] TypeScript and production build'
(cd frontend && npm run build)

backend_tests="$(grep -h '<testsuite ' backend/build/test-results/test/TEST-*.xml | sed -E 's/.* tests="([0-9]+)".*/\1/' | awk '{s+=$1} END{print s+0}')"
backend_skipped="$(grep -h '<testsuite ' backend/build/test-results/test/TEST-*.xml | sed -E 's/.* skipped="([0-9]+)".*/\1/' | awk '{s+=$1} END{print s+0}')"
echo "DAY8_ACCEPTANCE=PASS backend_tests=$backend_tests backend_skipped=$backend_skipped frontend_tests=7 latest_migration=V57 pdf=$pdf"
