PCAgent manual install set
==========================

이 설치 세트는 MSI / Windows Installer 없이 에이전트를 직접 복사 설치합니다.
Windows Update가 막혀 있거나 MSI 실행이 깨진 PC에서 사용하세요.

사용 방법
---------
1. 압축을 풀거나 폴더째 복사합니다.
2. install-agent.bat 을 관리자 권한으로 실행합니다.
3. 설치 후 C:\ProgramData\PCAgent\logs\agent.log 에서 동작을 확인합니다.

포함 파일
---------
- install-agent.bat
- uninstall-agent.bat
- agent.jar
- jre\\
- config.json
- agent-watchdog.ps1
- install-helper.ps1

특징
----
- MSI 불필요
- 시스템 Java 불필요 (번들 JRE 포함)
- 작업 스케줄러 자동 등록
- 재부팅 후 자동 시작
- 기존 config.json 이 있으면 agentToken 유지
