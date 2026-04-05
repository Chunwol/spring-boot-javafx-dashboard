# DB 스키마 요약

이 문서는 통합 대시보드 시스템의 주요 데이터베이스 테이블 구조를 요약합니다. (출처: FUNCTIONAL_SPECIFICATION.md)

---

| 테이블 | 주요 기능 | 주요 컬럼 |
|--------|----------|-----------|
| `users` | 사용자 인증 | username, password(BCrypt), email |
| `pc_master` | PC 레지스트리 | mac_address(PK), ip_address, group_id, status, role, last_seen, broken_note |
| `classroom_groups` | 강의실 정보 | id, name(UNIQUE), description |
| `classroom_ip_ranges` | IP 대역 | group_id(FK), cidr, start_ip, end_ip |
| `classroom_teacher_devices` | 교사 PC | group_id(FK), ip_address, mac_address |
| `traffic_logs` | 트래픽 기록 | mac_address(FK), inbound_kbps, outbound_kbps, timestamp |
| `command_queue` | 명령 큐 | mac_address(FK), command_type, status, payload_json, exit_code, stdout, stderr |
| `scripts` | 스크립트 저장 | original_filename, stored_filename, sha256, size_bytes |
| `dashboards` | 대시보드 | name(UNIQUE), description, display_order |
| `dashboard_panels` | 대시보드 패널 | dashboard_id(FK), panel_type, ref_group_id, x, y, width, height |
| `dashboard_widget_items` | 위젯 | dashboard_id, type, config(JSON), x, y, width, height |
| `agent_auth_tokens` | 에이전트 인증 | mac_address(FK), token_hash, revoked |
| `audit_logs` | 감사 로그 | actor, action, target_type, detail_json, ip_address |
| `user_settings` | 사용자 설정 | key(PK), value |

---

> 상세 컬럼, 인덱스, FK/PK 등은 backend/src/main/resources/db/migration/ 내 Flyway SQL 파일 참고.
> ERD, 관계도 등 추가가 필요하면 요청해 주세요.
