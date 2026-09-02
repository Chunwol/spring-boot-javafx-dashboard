import type { UserRole } from './types'

export type FeaturePermission =
  | 'CLASSROOM_VIEW'
  | 'PC_VIEW'
  | 'REMOTE_SCREEN_VIEW'
  | 'POWER_ON'
  | 'REBOOT'
  | 'SHUTDOWN'
  | 'SCMATE_STATUS_VIEW'
  | 'SCMATE_OPERATOR_MODE'
  | 'SCMATE_BASELINE_UPDATE'
  | 'SCMATE_PROTECTED_MODE'
  | 'SCMATE_SYSTEM_RESTORE'
  | 'SCMATE_RECOVERY_MODULE_INSTALL'
  | 'AGENT_UPDATE'
  | 'COMMAND_VIEW'
  | 'COMMAND_ISSUE'
  | 'COMMAND_CANCEL'
  | 'COMMAND_RESULT_DOWNLOAD'
  | 'SCRIPT_LIBRARY_VIEW'
  | 'SCRIPT_CREATE_EDIT'
  | 'SCRIPT_DELETE'
  | 'SCRIPT_EXECUTE'
  | 'TOKEN_VIEW'
  | 'AGENT_REGISTRATION_TOGGLE'
  | 'INSTALL_KEY_ROTATE'
  | 'AGENT_TOKEN_REVOKE'
  | 'AGENT_DELETE'
  | 'AGENT_CONFIG_DOWNLOAD'
  | 'BROKEN_PC_VIEW'
  | 'BROKEN_PC_REGISTER_EDIT'
  | 'BROKEN_PC_CLEAR'
  | 'NETWORK_VIEW'
  | 'REPORT_VIEW'
  | 'REPORT_EXPORT'
  | 'REPORT_REGENERATE'
  | 'FILE_DISTRIBUTION_VIEW'
  | 'FILE_UPLOAD_DISTRIBUTE'
  | 'FILE_JOB_CONTROL'
  | 'FILE_ROLLBACK'
  | 'PEER_FILE_TRANSFER'
  | 'CLASSROOM_SETTINGS_VIEW'
  | 'CLASSROOM_EDIT'
  | 'IP_RANGE_EDIT'
  | 'TEACHER_PC_EDIT'
  | 'IP_RANGE_PC_CONTROL'
  | 'SYSTEM_SETTINGS_VIEW'
  | 'ALERT_SETTINGS_EDIT'

export interface FeaturePermissionOption {
  value: FeaturePermission
  label: string
  description: string
}

export interface FeaturePermissionGroup {
  key: string
  label: string
  options: ReadonlyArray<FeaturePermissionOption>
}

export const FEATURE_PERMISSION_GROUPS: ReadonlyArray<FeaturePermissionGroup> = [
  { key: 'classroom', label: '강의실·PC 제어', options: [
    { value: 'CLASSROOM_VIEW', label: '강의실 현황 조회', description: '강의실 목록과 PC 상태를 봅니다.' },
    { value: 'PC_VIEW', label: 'PC 관리 조회', description: 'PC별 상세 상태와 목록을 봅니다.' },
    { value: 'REMOTE_SCREEN_VIEW', label: '원격 화면 보기·제어', description: '원격 화면과 다중 모니터링 세션을 사용합니다.' },
    { value: 'POWER_ON', label: '전원 켜기', description: '개별·강의실 WOL 명령을 발행합니다.' },
    { value: 'REBOOT', label: '재부팅', description: '선택 PC를 재부팅합니다.' },
    { value: 'SHUTDOWN', label: '종료', description: '선택 PC를 종료합니다.' },
    { value: 'AGENT_UPDATE', label: '에이전트 업데이트', description: '에이전트 JAR·번들 업데이트를 실행합니다.' },
  ] },
  { key: 'scmate', label: 'SCMATE 복구', options: [
    { value: 'SCMATE_STATUS_VIEW', label: '상세 상태 조회', description: 'SCMATE 모드와 복구 상태를 새로 조회합니다.' },
    { value: 'SCMATE_OPERATOR_MODE', label: '운영자 모드 전환', description: '운영자 모드 요청과 전환 재부팅을 실행합니다.' },
    { value: 'SCMATE_BASELINE_UPDATE', label: '현재 시점 저장', description: '현재 상태를 새 복구 시점으로 저장합니다.' },
    { value: 'SCMATE_PROTECTED_MODE', label: '보호 모드 전환', description: '보호 활성화와 보호 모드 재부팅을 실행합니다.' },
    { value: 'SCMATE_SYSTEM_RESTORE', label: '시스템 복구', description: '저장된 시점으로 시스템을 복구합니다.' },
    { value: 'SCMATE_RECOVERY_MODULE_INSTALL', label: '복구 모듈 설치', description: 'SCMATE 복구 모듈을 설치합니다.' },
  ] },
  { key: 'commands', label: '명령', options: [
    { value: 'COMMAND_VIEW', label: '명령 이력 조회', description: '명령 발행 이력과 결과를 봅니다.' },
    { value: 'COMMAND_ISSUE', label: '일반 명령 발행', description: 'CMD·PowerShell·파일 수집 명령을 발행합니다.' },
    { value: 'COMMAND_CANCEL', label: '대기 명령 취소', description: '아직 전달되지 않은 명령을 취소합니다.' },
    { value: 'COMMAND_RESULT_DOWNLOAD', label: '수집 결과 다운로드', description: '명령으로 수집한 파일을 내려받습니다.' },
  ] },
  { key: 'scripts', label: '스크립트 보관함', options: [
    { value: 'SCRIPT_LIBRARY_VIEW', label: '스크립트 조회', description: '보관 스크립트와 실행 이력을 봅니다.' },
    { value: 'SCRIPT_CREATE_EDIT', label: '스크립트 등록·수정', description: 'PS1 템플릿을 등록하거나 수정합니다.' },
    { value: 'SCRIPT_DELETE', label: '스크립트 삭제', description: '보관 스크립트를 삭제합니다.' },
    { value: 'SCRIPT_EXECUTE', label: '스크립트 실행·재시도', description: '선택 PC 실행, 재실행, 실패 재시도를 합니다.' },
  ] },
  { key: 'tokens', label: '에이전트·토큰', options: [
    { value: 'TOKEN_VIEW', label: '토큰 목록·설치 키 조회', description: '등록 에이전트와 설치 키를 봅니다.' },
    { value: 'AGENT_REGISTRATION_TOGGLE', label: '신규 등록 허용 전환', description: '신규 에이전트 등록을 열거나 닫습니다.' },
    { value: 'INSTALL_KEY_ROTATE', label: '설치 키 재발급', description: '기존 설치 키를 폐기하고 새 키를 만듭니다.' },
    { value: 'AGENT_TOKEN_REVOKE', label: '에이전트 토큰 폐기', description: '개별·선택 토큰을 폐기합니다.' },
    { value: 'AGENT_DELETE', label: '에이전트 삭제', description: '등록 정보와 토큰을 삭제합니다.' },
    { value: 'AGENT_CONFIG_DOWNLOAD', label: '설정 파일 다운로드', description: '에이전트 config.json을 내려받습니다.' },
  ] },
  { key: 'broken', label: '고장 PC', options: [
    { value: 'BROKEN_PC_VIEW', label: '고장 PC 조회', description: '고장·미사용 PC 목록을 봅니다.' },
    { value: 'BROKEN_PC_REGISTER_EDIT', label: '고장 등록·사유 수정', description: '고장 PC를 등록하고 사유를 수정합니다.' },
    { value: 'BROKEN_PC_CLEAR', label: '고장 상태 해제', description: '고장 또는 미사용 상태를 해제합니다.' },
  ] },
  { key: 'network', label: '네트워크', options: [
    { value: 'NETWORK_VIEW', label: '네트워크 현황 조회', description: '실시간 트래픽과 강의실별 사용량을 봅니다.' },
  ] },
  { key: 'reports', label: '보고서', options: [
    { value: 'REPORT_VIEW', label: '보고서 조회', description: '운영·가동률·트래픽 보고서를 봅니다.' },
    { value: 'REPORT_EXPORT', label: 'PDF 내보내기', description: '선택 기간 보고서를 PDF로 내려받습니다.' },
    { value: 'REPORT_REGENERATE', label: '월간 보고서 재생성', description: '월간 집계 자료를 다시 생성합니다.' },
  ] },
  { key: 'files', label: '파일 배포', options: [
    { value: 'FILE_DISTRIBUTION_VIEW', label: '배포 현황 조회', description: '파일·에이전트 배포 작업과 이력을 봅니다.' },
    { value: 'FILE_UPLOAD_DISTRIBUTE', label: '업로드·배포 생성', description: '파일을 올리고 새 배포 작업을 만듭니다.' },
    { value: 'FILE_JOB_CONTROL', label: '배포 시작·취소·재시도', description: '웨이브 시작, 취소와 실패 재시도를 합니다.' },
    { value: 'FILE_ROLLBACK', label: '배포 롤백', description: '이전 배포 파일로 되돌립니다.' },
    { value: 'PEER_FILE_TRANSFER', label: 'PC 간 직접 전송', description: '교사·학생 PC 사이 직접 파일 전송을 만듭니다.' },
  ] },
  { key: 'classroom-settings', label: '강의실 설정', options: [
    { value: 'CLASSROOM_SETTINGS_VIEW', label: '강의실 설정 조회', description: '강의실, IP 범위와 교사 PC 설정을 봅니다.' },
    { value: 'CLASSROOM_EDIT', label: '강의실·배치 편집', description: '강의실 추가·수정·삭제와 PC 배치를 변경합니다.' },
    { value: 'IP_RANGE_EDIT', label: 'IP 범위 편집', description: '강의실 자동 배정 IP 범위를 변경합니다.' },
    { value: 'TEACHER_PC_EDIT', label: '교사 PC 편집', description: '교사 PC 주소와 설명을 변경합니다.' },
    { value: 'IP_RANGE_PC_CONTROL', label: 'IP 범위 개별 제어', description: '미등록 PC를 포함한 IP 범위 제어 창을 사용합니다.' },
  ] },
  { key: 'system', label: '시스템 설정', options: [
    { value: 'SYSTEM_SETTINGS_VIEW', label: '시스템 설정 조회', description: '개인 시스템 설정 화면을 봅니다.' },
    { value: 'ALERT_SETTINGS_EDIT', label: '알림 표시 설정', description: '표시할 알림 유형을 변경합니다.' },
  ] },
]

export const FEATURE_PERMISSION_OPTIONS = FEATURE_PERMISSION_GROUPS.flatMap((group) => group.options)
export const ALL_FEATURE_PERMISSIONS = FEATURE_PERMISSION_OPTIONS.map((option) => option.value)

export const VIEWER_FEATURE_PERMISSIONS: FeaturePermission[] = [
  'CLASSROOM_VIEW', 'PC_VIEW', 'REMOTE_SCREEN_VIEW', 'BROKEN_PC_VIEW', 'NETWORK_VIEW',
  'REPORT_VIEW', 'CLASSROOM_SETTINGS_VIEW', 'SYSTEM_SETTINGS_VIEW', 'ALERT_SETTINGS_EDIT',
]

export const DEFAULT_FEATURE_PERMISSIONS: FeaturePermission[] = [
  'CLASSROOM_VIEW', 'PC_VIEW', 'REMOTE_SCREEN_VIEW', 'POWER_ON', 'REBOOT', 'SHUTDOWN', 'AGENT_UPDATE',
  'SCMATE_STATUS_VIEW', 'SCMATE_OPERATOR_MODE', 'SCMATE_BASELINE_UPDATE', 'SCMATE_PROTECTED_MODE',
  'SCMATE_SYSTEM_RESTORE', 'SCMATE_RECOVERY_MODULE_INSTALL',
  'BROKEN_PC_VIEW', 'BROKEN_PC_REGISTER_EDIT', 'BROKEN_PC_CLEAR', 'NETWORK_VIEW',
  'REPORT_VIEW', 'REPORT_EXPORT', 'REPORT_REGENERATE',
  'CLASSROOM_SETTINGS_VIEW', 'CLASSROOM_EDIT', 'IP_RANGE_EDIT', 'TEACHER_PC_EDIT', 'IP_RANGE_PC_CONTROL',
  'SYSTEM_SETTINGS_VIEW', 'ALERT_SETTINGS_EDIT',
]

const LEGACY_PERMISSION_MAP: Record<string, FeaturePermission[]> = {
  CLASSROOMS: ['CLASSROOM_VIEW', 'REMOTE_SCREEN_VIEW', 'POWER_ON', 'REBOOT', 'SHUTDOWN'],
  PCS: ['PC_VIEW', 'REMOTE_SCREEN_VIEW', 'POWER_ON', 'REBOOT', 'SHUTDOWN', 'SCMATE_STATUS_VIEW',
    'SCMATE_OPERATOR_MODE', 'SCMATE_BASELINE_UPDATE', 'SCMATE_PROTECTED_MODE', 'SCMATE_SYSTEM_RESTORE',
    'SCMATE_RECOVERY_MODULE_INSTALL', 'AGENT_UPDATE'],
  COMMANDS: ['COMMAND_VIEW', 'COMMAND_ISSUE', 'COMMAND_CANCEL', 'COMMAND_RESULT_DOWNLOAD', 'REMOTE_SCREEN_VIEW'],
  SCRIPT_LIBRARY: ['SCRIPT_LIBRARY_VIEW', 'SCRIPT_CREATE_EDIT', 'SCRIPT_DELETE', 'SCRIPT_EXECUTE'],
  TOKENS: ['TOKEN_VIEW', 'AGENT_REGISTRATION_TOGGLE', 'INSTALL_KEY_ROTATE', 'AGENT_TOKEN_REVOKE', 'AGENT_DELETE', 'AGENT_CONFIG_DOWNLOAD'],
  BROKEN_PCS: ['BROKEN_PC_VIEW', 'BROKEN_PC_REGISTER_EDIT', 'BROKEN_PC_CLEAR'],
  NETWORK: ['NETWORK_VIEW'],
  REPORTS: ['REPORT_VIEW', 'REPORT_EXPORT', 'REPORT_REGENERATE'],
  FILES: ['FILE_DISTRIBUTION_VIEW', 'FILE_UPLOAD_DISTRIBUTE', 'FILE_JOB_CONTROL', 'FILE_ROLLBACK', 'PEER_FILE_TRANSFER'],
  CLASSROOM_SETTINGS: ['CLASSROOM_SETTINGS_VIEW', 'CLASSROOM_EDIT', 'IP_RANGE_EDIT', 'TEACHER_PC_EDIT', 'IP_RANGE_PC_CONTROL'],
  SYSTEM_SETTINGS: ['SYSTEM_SETTINGS_VIEW', 'ALERT_SETTINGS_EDIT'],
}

export function normalizePermissions(role: UserRole, values?: readonly string[] | null): FeaturePermission[] {
  if (role === 'ADMIN') return [...ALL_FEATURE_PERMISSIONS]
  if (role === 'VIEWER') return [...VIEWER_FEATURE_PERMISSIONS]
  if (!Array.isArray(values)) return [...DEFAULT_FEATURE_PERMISSIONS]
  const valid = new Set<FeaturePermission>(ALL_FEATURE_PERMISSIONS)
  const expanded = values.flatMap((value) => LEGACY_PERMISSION_MAP[value] || [value])
  return [...new Set(expanded.filter((value): value is FeaturePermission => valid.has(value as FeaturePermission)))]
}

export function commandPermission(commandType: string): FeaturePermission {
  switch (commandType.trim().toUpperCase()) {
    case 'PCON': case 'WOL': case 'WAKEUP': return 'POWER_ON'
    case 'REBOOT': return 'REBOOT'
    case 'SHUTDOWN': case 'PCOFF': return 'SHUTDOWN'
    case 'AGENT_UPDATE': case 'AGENT_BUNDLE_UPDATE': return 'AGENT_UPDATE'
    case 'REMOTE_CONTROL_START': case 'REMOTE_CONTROL_STOP': return 'REMOTE_SCREEN_VIEW'
    case 'SCMATE_CONTROL': return 'SCMATE_SYSTEM_RESTORE'
    case 'RESTORE': return 'SCMATE_SYSTEM_RESTORE'
    default: return 'COMMAND_ISSUE'
  }
}

export function scmatePermission(action: string): FeaturePermission {
  switch (action.trim().toUpperCase()) {
    case 'REFRESH_STATUS_DETAIL': return 'SCMATE_STATUS_VIEW'
    case 'REQUEST_OPERATOR': case 'REBOOT_FOR_BASELINE': case 'REBOOT_TO_OPERATOR': return 'SCMATE_OPERATOR_MODE'
    case 'UPDATE_BASELINE': return 'SCMATE_BASELINE_UPDATE'
    case 'REQUEST_PROTECTED': case 'REBOOT_TO_PROTECTED': case 'ENABLE_PROTECTION': return 'SCMATE_PROTECTED_MODE'
    case 'RESTORE_BASELINE': return 'SCMATE_SYSTEM_RESTORE'
    case 'INSTALL_RECOVERY_MODULE': return 'SCMATE_RECOVERY_MODULE_INSTALL'
    default: return 'SCMATE_STATUS_VIEW'
  }
}
