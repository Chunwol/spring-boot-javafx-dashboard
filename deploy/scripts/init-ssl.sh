#!/bin/bash
# ======================================================
# init-ssl.sh  —  ZeroSSL 인증서 최초 발급 스크립트
# 사용법: deploy/ 디렉토리에서 실행
#   cd deploy && bash scripts/init-ssl.sh
# ======================================================
set -e

# deploy/ 디렉토리 기준으로 실행
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."   # → deploy/

# .env 파일 로드 (없으면 서버 환경변수 사용)
if [ -f .env ]; then
  export $(grep -v '^#' .env | xargs)
fi

DOMAIN=${DOMAIN:?"DOMAIN 환경변수를 설정하세요 (예: export DOMAIN=example.com)"}
EMAIL=${EMAIL:?"EMAIL 환경변수를 설정하세요"}
EAB_KID=${ZEROSSL_EAB_KID:?"ZEROSSL_EAB_KID 환경변수를 설정하세요"}
EAB_HMAC_KEY=${ZEROSSL_EAB_HMAC_KEY:?"ZEROSSL_EAB_HMAC_KEY 환경변수를 설정하세요"}

echo "=== [1/4] certbot webroot 디렉토리 준비 ==="
mkdir -p ./certbot/www ./certbot/conf

echo "=== [2/4] Nginx 시작 (HTTP only) ==="
docker compose up -d nginx
sleep 3

echo "=== [3/4] ZeroSSL 인증서 발급 중 (도메인: $DOMAIN) ==="
docker run --rm \
  -v "$(pwd)/certbot/conf:/etc/letsencrypt" \
  -v "$(pwd)/certbot/www:/var/www/certbot" \
  certbot/certbot certonly --webroot \
  -w /var/www/certbot \
  --server https://acme.zerossl.com/v2/DV90 \
  --eab-kid  "${EAB_KID}" \
  --eab-hmac-key "${EAB_HMAC_KEY}" \
  -d "${DOMAIN}" \
  --email "${EMAIL}" \
  --agree-tos --no-eff-email

echo "=== [4/4] HTTPS Nginx 설정 활성화 및 리로드 ==="
envsubst '${DOMAIN}' < nginx/conf.d/ssl.conf.disabled > nginx/conf.d/ssl.conf
docker compose exec nginx nginx -s reload

echo ""
echo "✅  HTTPS 설정 완료! https://${DOMAIN} 으로 접속하세요."
echo "📅  인증서 갱신: cd deploy && bash scripts/renew-ssl.sh"
