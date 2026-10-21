#!/usr/bin/env bash
# 一键启动后端（Docker）+ 前端（Vite preview）的本地开发环境。
#
# 为什么后端进容器：宿主机 Ubuntu 20.04 的 glibc 2.31 满足不了
# ai.djl.huggingface:tokenizers:0.36 对 GLIBC_2.32 的要求，
# 导致 BgeM3OnnxEmbeddingProvider 的 libtokenizers.so 加载失败。
# 容器镜像 eclipse-temurin:17-jre-jammy 的 glibc 2.35 可以正常加载，
# 从而恢复 BGE-M3 真向量（而不是降级到无语义的 hashing）。
#
# 用法：
#   ./scripts/dev-up.sh          # 构建并启动后端 + 前端
#   ./scripts/dev-up.sh stop     # 停止全部
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${PROJECT_DIR}/deploy/docker-compose.backend.yml"
SECRETS_FILE="${PROJECT_DIR}/deploy/.dev-secrets"
FRONTEND_LOG=/tmp/fb-preview.log

# ---------- 停止 ----------
if [[ "${1:-}" == "stop" ]]; then
  docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" down 2>/dev/null || true
  pkill -f "vite preview" 2>/dev/null || true
  echo "Stopped."
  exit 0
fi

# ---------- 1. JDK ----------
if [[ -z "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="${HOME}/.local/jdk/jdk-17.0.20.1+1"
fi
export PATH="${JAVA_HOME}/bin:${PATH}"

# ---------- 2. 密钥（从 600 权限的 my3307.cnf 读 DB，随机生成其余 ≥32 位） ----------
CNF=/tmp/fp-install/my3307.cnf
DB_USERNAME=$(grep -E '^user' "$CNF" | cut -d= -f2 | tr -d ' ')
DB_PASSWORD=$(grep -E '^password' "$CNF" | cut -d= -f2 | tr -d ' ')

# 固定生成一次后复用，避免每次重启密钥漂移导致历史数据指纹对不上。
if [[ ! -f "$SECRETS_FILE" ]]; then
  {
    echo "DB_USERNAME=${DB_USERNAME}"
    echo "DB_PASSWORD=${DB_PASSWORD}"
    echo "MASKING_FINGERPRINT_SECRET=$(openssl rand -hex 32)"
    echo "CONTACT_STORAGE_SECRET=$(openssl rand -hex 32)"
    echo "IMPORT_RAW_STORAGE_SECRET=$(openssl rand -hex 32)"
    for s in COURSE QUESTION_BANK WRONG_BOOK AI_TUTOR APP_STORE HEALTH_DEMO CHILD_ART_DEMO UNIVERSAL_FEEDBACK; do
      echo "${s}_SDK_SECRET=$(openssl rand -hex 32)"
    done
  } > "$SECRETS_FILE"
  chmod 600 "$SECRETS_FILE"
fi
set -a; # shellcheck disable=SC1090
source "$SECRETS_FILE"; set +a

# ---------- 3. 构建 bootJar + 镜像 ----------
echo "==> Building bootJar"
(cd "$PROJECT_DIR" && ./gradlew :backend:bootJar --no-daemon --console=plain -q)

echo "==> Building backend image"
docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" build backend

# ---------- 4. 启动后端 ----------
echo "==> Starting backend container"
docker compose -f "$COMPOSE_FILE" --env-file "$SECRETS_FILE" up -d backend

# 等待健康
for i in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:18080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    echo "==> Backend UP (http://127.0.0.1:18080)"
    break
  fi
  [[ $i -eq 90 ]] && { echo "Backend failed to start. Logs:"; docker logs feedback-platform-backend --tail 40; exit 1; }
  sleep 1
done

# ---------- 5. 前端 ----------
export NVM_DIR="${HOME}/.nvm"; [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm use v22.23.3 >/dev/null 2>&1 || true

if ! (exec 3<>/dev/tcp/127.0.0.1/4173) 2>/dev/null; then
  (cd "$PROJECT_DIR/frontend" && nohup npm run preview -- --port 4173 > "$FRONTEND_LOG" 2>&1 &)
  for i in $(seq 1 20); do
    curl -fsS http://127.0.0.1:4173/ >/dev/null 2>&1 && break; sleep 1
  done
fi
exec 3>&- 2>/dev/null || true

echo "==> Frontend UP (http://127.0.0.1:4173)"
echo "==> Done. Open http://127.0.0.1:4173/"
