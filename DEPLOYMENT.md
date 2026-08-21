# Deployment

현재 제품은 React 웹 화면을 포함한 단일 Spring Boot 애플리케이션과 Windows PC 에이전트로 배포합니다. JavaFX 클라이언트는 사용하지 않습니다.

운영 절차와 환경 변수는 다음 문서를 기준으로 합니다.

- [운영 배포](docs/DEPLOYMENT.md)
- [웹 프론트 배포](docs/WEB_FRONTEND_DEPLOYMENT.md)
- [환경 변수](docs/ENV_GUIDE.md)
- [이미지 갱신 체크리스트](docs/IMAGE_UPDATE_CHECKLIST.md)

NAS 전달용 이미지는 프로젝트 루트에서 아래 명령으로 만듭니다.

```powershell
deploy\scripts\build-and-export.bat
```
