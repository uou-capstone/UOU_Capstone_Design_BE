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
echo "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USERNAME}" --password-stdin
echo "[deploy] GHCR 로그인 완료"

# ── Compose 파일 및 env 파일 선택 ────────────────────────────
if [ "$TARGET_BRANCH" = "main" ]; then
  COMPOSE_FILE="docker-compose.main.yml"
  ENV_FILE="env/main.env"
else
  COMPOSE_FILE="docker-compose.develop.yml"
  ENV_FILE="env/develop.env"
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "[ERROR] ${ENV_FILE} 파일이 없습니다."
  echo "  cp ${ENV_FILE}.example ${ENV_FILE} 후 실제 값을 채워주세요."
  exit 1
fi

echo "[deploy] Compose 파일: ${COMPOSE_FILE}"
echo "[deploy] 환경 파일: ${ENV_FILE}"

# ── 이미지 pull ───────────────────────────────────────────────
echo "[deploy] 최신 이미지 pull 중..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull

# ── 컨테이너 재기동 ──────────────────────────────────────────
echo "[deploy] 컨테이너 재기동 중..."
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --remove-orphans

# ── 미사용 이미지 정리 ───────────────────────────────────────
docker image prune -f

echo "[deploy] 완료 ✓ (브랜치: ${TARGET_BRANCH})"
echo "[deploy] Spring: http://$(hostname -I | awk '{print $1}'):$([ "$TARGET_BRANCH" = "main" ] && echo 8080 || echo 8081)/api/health"
echo "[deploy] AI   : http://$(hostname -I | awk '{print $1}'):$([ "$TARGET_BRANCH" = "main" ] && echo 8000 || echo 8001)/health"
