PCAgent manual install set
==========================

이 설치 세트는 MSI / Windows Installer 없이 에이전트를 직접 복사 설치합니다.
Windows Update가 막혀 있거나 MSI 실행이 깨진 PC에서 사용하세요.

사용 방법
---------
1. 압축을 풀거나 폴더째 복사합니다.
2. install-agent.bat 을 더블클릭하고 관리자 권한 요청을 승인합니다.
3. 마지막에 "Agent heartbeat confirmed"가 표시되는지 확인합니다.
   느린 PC를 고려해 java.exe 프로세스와 heartbeat를 최대 5분간 확인합니다.
4. 확인되지 않으면 C:\ProgramData\PCAgent\logs\manual-install.log 와 agent.log를 확인합니다.
5. 정상 확인된 로컬 설치 폴더는 창이 닫힌 뒤 자동으로 삭제됩니다.

포함 파일
---------
- install-agent.bat
- uninstall-agent.bat
- agent.jar
- jre\\
- config.json
- agent-watchdog.ps1
- install-helper.ps1
- agent-bundle-updater.ps1
- bundle-manifest.json
- manual-install-cleanup.ps1

특징
----
- MSI 불필요
- 시스템 Java를 사용하지 않음 (검증된 번들 Java 25만 사용)
- 손상된 기존 runtime은 자동으로 건너뛰고 정상 번들 JRE로 복구
- 작업 스케줄러 자동 등록
- SYSTEM 계정 + 숨김 PowerShell로 실행되어 부팅 때 명령창이 표시되지 않음
- 재부팅 후 자동 시작
- 기존 config.json 이 있으면 agentToken 유지
- 기존 MSI 설치 PC에서는 app\agent.jar를 교체하여 이전 버전 재실행 방지
- UltraVNC 번들 포함
- 이후 JRE, UltraVNC, 워치독까지 무중단 전체 번들 업데이트 지원
- 네트워크 공유/USB에서 직접 실행하거나 설치 확인에 실패한 경우 원본 파일 보존
- 성공 또는 5분 확인 종료 후 설치 창 자동 닫힘
