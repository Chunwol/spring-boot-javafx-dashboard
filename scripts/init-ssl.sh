#!/bin/bash
# ======================================================
# init-ssl.sh  —  ZeroSSL 인증서 최초 발급 스크립트
# 사용법: bash scripts/init-ssl.sh
# ======================================================
set -e

# .env 파일 로드
if [ -f .env ]; then
  export $(grep -v '^#' .env | xargs)
fi

DOMAIN=${DOMAIN:-pelmon.kro.kr}
EMAIL=${EMAIL:-heeya6145@dongyang.ac.kr}
EAB_KID=${ZEROSSL_EAB_KID:?".env에 ZEROSSL_EAB_KID를 설정하세요"}
EAB_HMAC_KEY=${ZEROSSL_EAB_HMAC_KEY:?".env에 ZEROSSL_EAB_HMAC_KEY를 설정하세요"}

echo "=== [1/4] certbot webroot 디렉토리 준비 ==="
mkdir -p certbot/www certbot/conf

echo "=== [2/4] Nginx 시작 (HTTP only) ==="
docker compose up -d nginx

# Nginx 시작 시간 대기
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
# ssl.conf.disabled → ssl.conf 활성화
cp nginx/conf.d/ssl.conf.disabled nginx/conf.d/ssl.conf

# Nginx 리로드
docker compose exec nginx nginx -s reload

echo ""
echo "✅  HTTPS 설정 완료!"
echo "    https://${DOMAIN} 으로 접속하세요."
echo ""
echo "📅  인증서 갱신은 다음 명령으로 실행하세요:"
echo "    bash scripts/renew-ssl.sh"
