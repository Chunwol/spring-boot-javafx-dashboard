#!/bin/bash
# ======================================================
# renew-ssl.sh  —  ZeroSSL 인증서 갱신 스크립트
# 사용법: deploy/ 디렉토리에서 실행
#   cd deploy && bash scripts/renew-ssl.sh
# 크론탭 예시 (60일마다):
#   0 0 1 */2 * cd /path/to/deploy && bash scripts/renew-ssl.sh
# ======================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."   # → deploy/

# .env 파일 로드 (없으면 서버 환경변수 사용)
if [ -f .env ]; then
  export $(grep -v '^#' .env | xargs)
fi

EAB_KID=${ZEROSSL_EAB_KID:?"ZEROSSL_EAB_KID 환경변수를 설정하세요"}
EAB_HMAC_KEY=${ZEROSSL_EAB_HMAC_KEY:?"ZEROSSL_EAB_HMAC_KEY 환경변수를 설정하세요"}

echo "=== [1/2] ZeroSSL 인증서 갱신 중 ==="
docker run --rm \
  -v "$(pwd)/certbot/conf:/etc/letsencrypt" \
  -v "$(pwd)/certbot/www:/var/www/certbot" \
  certbot/certbot renew \
  --server https://acme.zerossl.com/v2/DV90 \
  --eab-kid  "${EAB_KID}" \
  --eab-hmac-key "${EAB_HMAC_KEY}"

echo "=== [2/2] Nginx 리로드 ==="
docker compose exec nginx nginx -s reload

echo "✅  인증서 갱신 완료!"
