#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
fixture="$(mktemp)"
trap 'rm -f "$fixture"' EXIT
cat >"$fixture" <<EOF
101 java -cp $project_dir/backend/build/classes/kotlin/main com.feedback.FeedbackApplicationKt --server.port=18080
102 java -cp /tmp/another-project/backend com.feedback.FeedbackApplicationKt --server.port=18081
103 java -cp $project_dir/backend/build/classes/kotlin/main com.example.OtherApplicationKt
104 java -jar $project_dir/backend/build/libs/backend-1.0.0-SNAPSHOT.jar --server.port=18082
105 java -cp $project_dir/backend/build/libs/backend-1.0.0-SNAPSHOT.jar com.feedback.tools.AttachmentObjectMigrationMainKt
106 java -Dfeedback.project.root=$project_dir -jar /tmp/feedback-platform-local/backend-runtime.jar --server.port=18083
107 java -Dfeedback.project.root=/tmp/another-project -jar /tmp/feedback-platform-local/backend-runtime.jar --server.port=18084
EOF
export BACKEND_PROCESS_LIST_FILE="$fixture"
source "$project_dir/tools/backend-instance-guard.sh"
instances=()
instance_output="$(feedback_backend_instances "$project_dir")"
while IFS= read -r instance_pid; do [[ -n "$instance_pid" ]] && instances+=("$instance_pid"); done <<<"$instance_output"
[[ "${#instances[@]}" -eq 3 && "${instances[0]}" == "101" && "${instances[1]}" == "104" && "${instances[2]}" == "106" ]]
printf 'backend instance guard test passed\n'
