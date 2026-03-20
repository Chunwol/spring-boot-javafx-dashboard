-- 초기 데이터 삽입
-- 관리자 계정은 DefaultUserInitializer가 시작 시 .env의 DEFAULT_ADMIN_* 값으로 BCrypt 해시화하여 생성합니다.
-- 여기에 평문 비밀번호를 하드코딩하지 않습니다.

INSERT INTO classroom_groups (name, description) VALUES ('강의실 101', '1층 강의실');
INSERT INTO classroom_groups (name, description) VALUES ('강의실 102', '1층 강의실');

INSERT INTO classroom_ip_ranges (group_id, cidr, start_ip, end_ip, description) VALUES (1, '192.168.10.0/24', '192.168.10.1', '192.168.10.254', '강의실 101 IP 범위');
INSERT INTO classroom_ip_ranges (group_id, cidr, start_ip, end_ip, description) VALUES (2, '192.168.11.0/24', '192.168.11.1', '192.168.11.254', '강의실 102 IP 범위');

INSERT INTO classroom_teacher_devices (group_id, ip_address, mac_address) VALUES (1, '192.168.10.1', '00:0A:95:9D:68:10');
INSERT INTO classroom_teacher_devices (group_id, ip_address, mac_address) VALUES (2, '192.168.11.1', '00:0A:95:9D:68:11');

INSERT INTO pc_master (mac_address, pc_name, ip_address, group_id, status, role) VALUES ('00:0A:95:9D:68:10', 'TEACHER-PC-101', '192.168.10.1', 1, 'ONLINE', 'TEACHER');
INSERT INTO pc_master (mac_address, pc_name, ip_address, group_id, status, role) VALUES ('00:0A:95:9D:68:11', 'TEACHER-PC-102', '192.168.11.1', 2, 'ONLINE', 'TEACHER');
INSERT INTO pc_master (mac_address, pc_name, ip_address, group_id, status, role) VALUES ('00:0A:95:9D:68:12', 'STUDENT-PC-101-1', '192.168.10.2', 1, 'OFFLINE', 'STUDENT');
INSERT INTO pc_master (mac_address, pc_name, ip_address, group_id, status, role) VALUES ('00:0A:95:9D:68:13', 'STUDENT-PC-101-2', '192.168.10.3', 1, 'ONLINE', 'STUDENT');

INSERT INTO dashboard_modules (group_id, type, config, pos_x, pos_y) VALUES (1, 'PC_STATUS', '{"showOffline": true}', 0, 0);
INSERT INTO dashboard_modules (group_id, type, config, pos_x, pos_y) VALUES (2, 'TRAFFIC_CHART', '{"period": "HOURLY"}', 200, 0);