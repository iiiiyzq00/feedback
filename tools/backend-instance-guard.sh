#!/usr/bin/env bash

feedback_backend_instances() {
  local project_dir="$1"
  local process_rows
  if [[ -n "${BACKEND_PROCESS_LIST_FILE:-}" ]]; then
    process_rows="$(cat "$BACKEND_PROCESS_LIST_FILE")"
  else
    process_rows="$(ps -axo pid=,command=)"
  fi
  # 仅识别 Web 主类、构建目录中的后端包，或带项目标记的独立运行制品；
  # 迁移工具虽复用后端 classpath，但不是 Web 实例。
  printf '%s\n' "$process_rows" | awk -v root="$project_dir" '
    index($0,root) && (index($0,"com.feedback.FeedbackApplicationKt") || $0 ~ /[[:space:]]-jar[[:space:]][^[:space:]]*\/backend\/build\/libs\/(backend-|feedback-platform-backend)[^[:space:]]*\.jar([[:space:]]|$)/ || (index($0,"-Dfeedback.project.root=" root) && $0 ~ /[[:space:]]-jar[[:space:]]/)) {
      pid=$1
      print pid
    }'
}

describe_feedback_backend_instance() {
  local pid="$1"
  local ports=""
  if command -v lsof >/dev/null 2>&1; then
    ports="$(lsof -nP -a -p "$pid" -iTCP -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {sub(/^.*:/,"",$9); print $9}' | paste -sd, -)"
  fi
  printf 'pid=%s%s' "$pid" "${ports:+ ports=$ports}"
}
