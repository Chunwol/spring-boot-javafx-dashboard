#!/bin/bash
# ======================================================
# build-and-export.sh — 로컬 빌드 후 NAS 전달용 이미지 생성
# 사용법: 프로젝트 루트에서 실행
#   bash deploy/scripts/build-and-export.sh
# ======================================================
set -e

IMAGE_NAME="classroom-dashboard"
IMAGE_TAG="latest"
OUTPUT_FILE="deploy/classroom-dashboard.tar.gz"

# 프로젝트 루트 기준으로 실행
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/../.."   # → 프로젝트 루트

echo "================================================"
echo " Classroom Dashboard — Docker 이미지 빌드"
echo "================================================"
echo ""

echo "[1/3] Docker 이미지 빌드 중..."
docker build \
  -f deploy/Dockerfile \
  -t "${IMAGE_NAME}:${IMAGE_TAG}" \
  .

echo ""
echo "[2/3] 이미지 파일로 내보내는 중... (시간이 걸릴 수 있습니다)"
docker save "${IMAGE_NAME}:${IMAGE_TAG}" | gzip > "${OUTPUT_FILE}"

SIZE=$(du -sh "${OUTPUT_FILE}" | cut -f1)
echo ""
echo "================================================"
echo " ✅ 완료!"
echo "    파일: ${OUTPUT_FILE}"
echo "    크기: ${SIZE}"
echo "================================================"
echo ""
echo "NAS에서 실행 순서:"
echo "  1. deploy/ 폴더 전체를 NAS에 복사"
echo "  2. NAS에서:"
echo "     docker load < classroom-dashboard.tar.gz"
echo "     (또는: docker load -i classroom-dashboard.tar.gz)"
echo "  3. cd deploy && docker compose up -d"
