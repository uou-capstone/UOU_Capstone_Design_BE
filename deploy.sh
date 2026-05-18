#!/bin/bash
# EC2 배포 스크립트
#
# 사용법:
#   bash deploy.sh <브랜치>
#   bash deploy.sh main       → docker-compose.main.yml (포트 8080/8000)
#   bash deploy.sh develop    → docker-compose.develop.yml (포트 8081/8001)
#
# 필수 환경변수 (GitHub Actions Secrets 또는 EC2 ~/.bashrc 에 설정):
#   GHCR_TOKEN      : GHCR pull 권한이 있는 Personal Access Token
#   GHCR_USERNAME   : GitHub 사용자명 (또는 org명)
#
# 롤백 방법:
#   env/main.env (또는 env/develop.env) 에서
#   SPRING_IMAGE_TAG / AI_IMAGE_TAG 를 이전 SHA 태그로 수정 후 재실행

set -euo pipefail

REPO_DIR="/home/ec2-user/UOU_Capstone_design_Be_v3"
TARGET_BRANCH="${1:-develop}"
NGINX_CONF="${NGINX_CONF:-/etc/nginx/conf.d/uouaitutor.conf}"
DEPLOY_ENV_FILE="${DEPLOY_ENV_FILE:-/etc/uou-capstone/deploy.env}"

echo "[deploy] 브랜치: ${TARGET_BRANCH}"
echo "[deploy] 저장소 디렉토리: ${REPO_DIR}"

# ── 저장소 최신화 ─────────────────────────────────────────────
# git pull 대신 fetch + checkout + reset 사용.
# pull은 현재 체크아웃된 브랜치에 merge하므로, main/develop 두 환경이
# 같은 디렉토리를 공유할 때 엉뚱한 브랜치에 merge될 위험이 있음.
cd "$REPO_DIR"
git fetch origin "$TARGET_BRANCH"
git checkout "$TARGET_BRANCH"
git reset --hard "origin/$TARGET_BRANCH"

# ── GHCR 로그인 ───────────────────────────────────────────────
if [ -f "$DEPLOY_ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$DEPLOY_ENV_FILE"
  set +a
fi

: "${GHCR_TOKEN:?GHCR_TOKEN is required. Set it in /etc/uou-capstone/deploy.env or the environment.}"
: "${GHCR_USERNAME:?GHCR_USERNAME is required. Set it in /etc/uou-capstone/deploy.env or the environment.}"

echo "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USERNAME}" --password-stdin
echo "[deploy] GHCR 로그인 완료"

# ── Compose 파일 및 env 파일 선택 ────────────────────────────
if [ "$TARGET_BRANCH" = "main" ]; then
  COMPOSE_FILE="docker-compose.main.yml"
  ENV_FILE="env/main.env"
  SPRING_PORT="8080"
  AI_PORT="8000"
else
  COMPOSE_FILE="docker-compose.develop.yml"
  ENV_FILE="env/develop.env"
  SPRING_PORT="8081"
  AI_PORT="8001"
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "[ERROR] ${ENV_FILE} 파일이 없습니다."
  echo "  cp ${ENV_FILE}.example ${ENV_FILE} 후 실제 값을 채워주세요."
  exit 1
fi

echo "[deploy] Compose 파일: ${COMPOSE_FILE}"
echo "[deploy] 환경 파일: ${ENV_FILE}"
echo "[deploy] Spring 포트: ${SPRING_PORT}"
echo "[deploy] AI 포트: ${AI_PORT}"

# ── 이미지 pull ───────────────────────────────────────────────
echo "[deploy] 최신 이미지 pull 중..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull

# ── 컨테이너 재기동 ──────────────────────────────────────────
echo "[deploy] 컨테이너 재기동 중..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --remove-orphans

# ── Nginx 프록시 대상 전환 ───────────────────────────────────
# 같은 도메인을 main/develop 배포에 번갈아 붙일 수 있도록 브랜치별 포트로 갱신.
if command -v nginx >/dev/null 2>&1; then
  if sudo -n test -f "$NGINX_CONF"; then
    echo "[deploy] Nginx 프록시 대상 갱신 중: ${NGINX_CONF}"
    sudo -n sed -i -E -e "s|proxy_pass http://127\\.0\\.0\\.1:808[01]/;|proxy_pass http://127.0.0.1:${SPRING_PORT}/;|g" -e "s|proxy_pass http://127\\.0\\.0\\.1:800[01]/;|proxy_pass http://127.0.0.1:${AI_PORT}/;|g" "$NGINX_CONF"
    sudo -n nginx -t
    sudo -n systemctl reload nginx
    echo "[deploy] Nginx reload 완료"
  else
    echo "[deploy] Nginx 설정 파일이 없거나 sudo 권한이 없어 프록시 전환을 건너뜁니다: ${NGINX_CONF}"
  fi
else
  echo "[deploy] Nginx가 없어 프록시 전환을 건너뜁니다."
fi

# ── 미사용 이미지 정리 ───────────────────────────────────────
docker image prune -f

echo "[deploy] 완료 ✓ (브랜치: ${TARGET_BRANCH})"
echo "[deploy] Spring: http://$(hostname -I | awk '{print $1}'):${SPRING_PORT}/api/health"
echo "[deploy] AI   : http://$(hostname -I | awk '{print $1}'):${AI_PORT}/health"
