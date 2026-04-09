#!/bin/bash
# ======================================================
# renew-ssl.sh  —  ZeroSSL 인증서 갱신 스크립트
# 크론탭 예시 (60일마다): 0 0 */60 * * bash /path/to/scripts/renew-ssl.sh
# ======================================================
set -e

# 프로젝트 루트로 이동
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."

# .env 파일 로드
if [ -f .env ]; then
  export $(grep -v '^#' .env | xargs)
fi

EAB_KID=${ZEROSSL_EAB_KID:?".env에 ZEROSSL_EAB_KID를 설정하세요"}
EAB_HMAC_KEY=${ZEROSSL_EAB_HMAC_KEY:?".env에 ZEROSSL_EAB_HMAC_KEY를 설정하세요"}

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
