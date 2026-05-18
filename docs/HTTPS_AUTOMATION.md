# HTTPS 자동 발급·갱신 자동화 안내

이 문서는 프로젝트(백엔드/프론트엔드)에 대해 HTTPS 인증서를 자동으로 발급하고 갱신하는 방법을 정리합니다. 운영 환경(퍼블릭 도메인, 포트 개방 등)에 따라 세 가지 권장 접근법과 설정 예시를 제공합니다.

요구사항
- 유효한 퍼블릭 도메인(예: `example.com`)이 서버(혹은 리버스 프록시)로 포인팅되어 있어야 합니다.
- 포트 80(HTTP)과 443(HTTPS)이 외부에서 접근 가능해야 합니다(HTTP-01 챌린지 사용 시).
- 인증서·키 저장소는 이미지 클론/스냅샷 환경에서도 영속화할 위치에 두세요(볼륨 또는 호스트 경로).

권장 옵션
1. Traefik (Docker 환경에서 권장): 컨테이너 오케스트레이션이 있거나 Docker로 서비스 운영 시 자동 발급·갱신이 쉽습니다.
2. Caddy (단일 바이너리 + 자동 TLS): 간단한 리버스프록시가 필요할 때 빠르게 적용 가능.
3. nginx + Certbot: 기존 nginx 사용 환경에서 범용적.
4. Windows 서버 전용: win-acme(윈-에이씨엠이)로 인증서를 발급하고 PFX/PKCS12로 내보내어 Spring Boot에 연결.

---

옵션 A — Traefik (도커) 예시
- 장점: 자동 발급·자동 갱신이 내장되어 있고 Docker 라벨로 라우팅 구성 가능.
- 제한: Docker 사용 환경 필요, 포트 80/443 개방 필요.

예시 `scripts/traefik-docker-compose.yml` (샘플)
```yaml
version: "3.7"
services:
  traefik:
    image: traefik:v2.10
    command:
      - --api.insecure=true
      - --providers.docker=true
      - --entrypoints.web.address=:80
      - --entrypoints.websecure.address=:443
      - --certificatesresolvers.le.acme.httpChallenge.entryPoint=web
      - --certificatesresolvers.le.acme.email=you@example.com # 변경 필요
      - --certificatesresolvers.le.acme.storage=/letsencrypt/acme.json
    ports:
      - "80:80"
      - "443:443"
      - "8080:8080" # 대시보드(옵션)
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - ./data/letsencrypt:/letsencrypt
    restart: unless-stopped

  backend:
    image: your-backend-image:latest
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.backend.rule=Host(`example.com`)" # 변경 필요
      - "traefik.http.routers.backend.entrypoints=websecure"
      - "traefik.http.routers.backend.tls=true"
      - "traefik.http.routers.backend.tls.certresolver=le"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
```
설치/실행
- `scripts/traefik-docker-compose.yml`에서 `you@example.com`, `example.com`, `your-backend-image` 값을 변경합니다.
- `docker compose -f scripts/traefik-docker-compose.yml up -d`

Traefik은 자동으로 인증서를 발급·갱신합니다. 인증 정보는 `./data/letsencrypt/acme.json`에 저장되므로 이 디렉토리는 영속화(볼륨, 호스트 경로)해야 합니다.

---

옵션 B — nginx + Certbot (리눅스 전통적 방식)
1. nginx가 설치되어 있고 도메인이 포인팅 되어 있을 때 사용합니다.
2. Certbot을 사용하여 인증서를 발급합니다.

간단 예시 (Ubuntu)
```bash
sudo apt update
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d example.com -m you@example.com --agree-tos --no-eff-email
```
발급 후 nginx 설정에서 `ssl_certificate` 및 `ssl_certificate_key`를 사용합니다.
Spring Boot 직접 사용 시(standalone):
```bash
# PEM -> PKCS12 변환
openssl pkcs12 -export -in /etc/letsencrypt/live/example.com/fullchain.pem \
  -inkey /etc/letsencrypt/live/example.com/privkey.pem \
  -out keystore.p12 -name tomcat -password pass:changeit
```
application.properties 예시:
```
server.port=443
server.ssl.key-store=/path/to/keystore.p12
server.ssl.key-store-password=changeit
server.ssl.key-store-type=PKCS12
server.ssl.key-alias=tomcat
```
Certbot 갱신은 cron/systemd-timer로 자동화됩니다(보통 설치 시 자동 등록).

---

옵션 C — Windows: win-acme 사용
- Windows 환경에서 자동화하려면 `win-acme`(https://www.win-acme.com/)를 이용해 인증서를 발급하고 PFX로 내보내 향후 갱신을 Windows 작업 스케줄러에 등록합니다.
- 예시 스크립트 `scripts/win-acme-setup.ps1`를 참고하세요(초기 발급을 자동화하는 헬퍼 템플릿).

중요: win-acme 명령은 환경(관리자 권한, 방화벽, 포트 개방)에 따라 달라집니다. 스크립트는 템플릿이며 운영 환경에 맞게 조정해야 합니다.

---

운영상 주의사항
- 퍼블릭 도메인 필요: Let’s Encrypt는 공개 도메인 소유 검증이 필요합니다. 로컬 `localhost`용은 발급 불가.
- 포트 포워딩/방화벽: HTTP-01 챌린지는 80 포트, TLS-ALPN 또는 DNS 챌린지는 각각의 전제조건이 있으니 확인하세요.
- 인증서 파일 영속성: 인증서 저장소(예: `./data/letsencrypt`, Windows PFX 폴더)는 VM 이미지 클론/스냅샷 환경에서 영속화된 위치여야 합니다.
- 자동갱신 테스트: 적용 후 `--dry-run` 또는 Traefik/Caddy의 로그로 자동 갱신 동작을 확인하세요.

테스트 권장 순서
1. 도메인 DNS가 서버를 가리키는지 확인 (`dig`/`nslookup`).
2. 포트 80/443 연결 확인 (`curl -v http://example.com`/`https://example.com`).
3. Traefik 예시로 먼저 테스트(컨테이너 격리) → nginx/원격 배포 순으로 진행.

다음 단계(선택)
- 제가 도메인(예: example.com, 이메일) 값을 받아 `scripts/traefik-docker-compose.yml`을 사용하여 Traefik 구성과 백엔드 라벨을 실제로 적용해 드리겠습니다.
- 또는 Windows용 `win-acme` 스크립트를 환경값으로 채워 자동 발급 테스트를 준비해 드리겠습니다.

파일:
- `scripts/traefik-docker-compose.yml` (샘플 구성)
- `scripts/win-acme-setup.ps1` (윈도우 헬퍼 스크립트)

문의: 어떤 방식(도커+Traefik / nginx+Certbot / Windows+win-acme)을 원하시는지 알려주시면, 도메인/이메일 값으로 직접 적용해 드리겠습니다.

**백엔드 전용(Agent 제외) 권장 구성**

- 본 문서의 예시는 백엔드 서비스(`backend`)에 대한 TLS 종료(리버스 프록시에서 인증서 적용)를 전제로 합니다. 에이전트(`agent`)는 퍼블릭 인증서를 설치할 필요가 없으며, 가능한 경우 퍼블릭 네트워크에 직접 노출하지 마십시오.
- 권장 아키텍처:
  - 퍼블릭 트래픽: Traefik/nginx/Caddy 등 리버스 프록시가 TLS를 종료하고 백엔드로 프록시합니다.
  - 에이전트 통신: 에이전트는 내부 네트워크, VPN 또는 NAT 뒤에서 백엔드에 접속하도록 구성하고, 에이전트 쪽에는 퍼블릭 인증서를 배포하지 않습니다.
  - 인증서 저장: 인증서와 키는 리버스프록시/백엔드가 접근 가능한 영속 저장소(호스트 볼륨, 별도 디스크)에만 보관하세요. 이미지/스냅샷에 인증서를 포함하지 마십시오.
- 보안 권장사항:
  - 에이전트↔백엔드 트래픽을 추가로 보호하려면 내부 전용 TLS(사설 CA) 또는 mTLS를 고려하세요.
  - 퍼블릭 엔드포인트는 최소화하고 방화벽 규칙으로 에이전트 접근을 제한하세요.
  - 에이전트가 복제 이미지로 배포되는 환경에서는 인증서/키 유출 가능성을 줄이기 위해 에이전트 이미지에 인증서 자격 정보를 포함하지 않는 것이 안전합니다.
