import type { UserRole } from './types'

export type FeaturePermission =
  | 'CLASSROOMS'
  | 'PCS'
  | 'COMMANDS'
  | 'SCRIPT_LIBRARY'
  | 'TOKENS'
  | 'BROKEN_PCS'
  | 'NETWORK'
  | 'REPORTS'
  | 'FILES'
  | 'CLASSROOM_SETTINGS'
  | 'SYSTEM_SETTINGS'

export const FEATURE_PERMISSION_OPTIONS: ReadonlyArray<{
  value: FeaturePermission
  label: string
  description: string
}> = [
  { value: 'CLASSROOMS', label: '강의실 현황', description: '강의실 상태 조회와 전원 제어 및 화면 모니터링' },
  { value: 'PCS', label: 'PC 관리', description: 'PC별 상태 조회, 전원·복구·에이전트 작업' },
  { value: 'COMMANDS', label: '명령 발행 / 이력', description: '명령 발행, 취소, 이력과 원격 접속 현황 조회' },
  { value: 'SCRIPT_LIBRARY', label: '스크립트 보관함', description: '운영 스크립트 등록과 실행' },
  { value: 'TOKENS', label: '토큰 관리', description: '에이전트 등록 및 설치키·토큰 관리' },
  { value: 'BROKEN_PCS', label: '고장 PC', description: '고장 PC 등록과 해제' },
  { value: 'NETWORK', label: '네트워크', description: '실시간 트래픽과 강의실별 네트워크 현황' },
  { value: 'REPORTS', label: '보고서', description: '운영·가동률·트래픽 보고서 조회와 생성' },
  { value: 'FILES', label: '파일 배포', description: '학생 PC 대상 파일 및 에이전트 배포' },
  { value: 'CLASSROOM_SETTINGS', label: '강의실 설정', description: '강의실, IP 범위와 교사 PC 관리' },
  { value: 'SYSTEM_SETTINGS', label: '시스템 설정', description: '개인 화면 설정과 계정 비밀번호 변경' },
]

export const ALL_FEATURE_PERMISSIONS = FEATURE_PERMISSION_OPTIONS.map((option) => option.value)

export const DEFAULT_FEATURE_PERMISSIONS: FeaturePermission[] = [
  'CLASSROOMS', 'PCS', 'BROKEN_PCS', 'NETWORK', 'REPORTS', 'CLASSROOM_SETTINGS', 'SYSTEM_SETTINGS',
]

export function normalizePermissions(role: UserRole, values?: readonly string[] | null): FeaturePermission[] {
  if (role === 'ADMIN') return [...ALL_FEATURE_PERMISSIONS]
  if (!Array.isArray(values)) return [...DEFAULT_FEATURE_PERMISSIONS]
  const valid = new Set<FeaturePermission>(ALL_FEATURE_PERMISSIONS)
  return [...new Set(values.filter((value): value is FeaturePermission => valid.has(value as FeaturePermission)))]
}
