# 환경 변수 가이드 (ENV_GUIDE.md)

이 문서는 .env 파일 및 환경 변수 설정 방법을 안내합니다.

---

## 1. .env 파일 위치
- 프로젝트 루트: .env
- Docker 환경: docker-compose.yml에서 자동 로드

## 2. 주요 환경 변수 목록
| 변수 | 예시 | 설명 |
|------|------|------|
| SERVER_PORT | 8888 | 백엔드 서버 포트 |
| JWT_SECRET | (랜덤문자열) | JWT 서명키 (32자 이상) |
| AGENT_INSTALL_KEY | INST-xxxxxx | 에이전트 설치 인증키 |
| DB_URL | jdbc:sqlite:db/mydatabase.db | DB 연결 문자열 |
| LOG_LEVEL | INFO | 로그 레벨 (DEBUG/INFO/WARN) |
| ALERT_EMAIL | admin@school.ac.kr | 경보 이메일 수신자 |
| MAIL_HOST | smtp.gmail.com | SMTP 서버 주소 |
| MAIL_PORT | 587 | SMTP 포트 |
| MAIL_USERNAME | school-alert@gmail.com | SMTP 계정 |
| MAIL_PASSWORD | (앱 비밀번호) | SMTP 비밀번호 |

## 3. 환경 변수 적용 방법
- .env 파일 수정 후 서비스 재시작 필요
- Docker: docker-compose down/up -d
- 수동: gradlew 재실행

## 4. 보안 권장 사항
- JWT_SECRET, AGENT_INSTALL_KEY 등은 외부 유출 금지
- .env 파일은 git에 커밋하지 않음 (.gitignore)

---

> 상세 환경 변수 설명 및 예시는 ADMIN_OPERATIONS_GUIDE.md 참고
