import type { PcStatus, ScmateRecoveryPoint } from '../../types/dashboard'

export type PcStateFilter = 'all' | 'online' | 'offline' | 'broken'
export type PcRoleFilter = 'all' | 'teacher' | 'student'
export type ScmateFilter = 'all' | 'server' | 'operator' | 'protected' | 'transition'
  | 'restart' | 'daily-first-boot' | 'weekly'
  | 'baseline' | 'snap1' | 'snap2'
  | 'not-installed' | 'disabled' | 'license-expired' | 'error' | 'unknown'
export type PcPowerCommand = 'PCON' | 'REBOOT' | 'SHUTDOWN'
export const SCMATE_ACTIONS = [
  'REQUEST_OPERATOR',
  'REBOOT_FOR_BASELINE',
  'REBOOT_TO_OPERATOR',
  'UPDATE_BASELINE',
  'REQUEST_PROTECTED',
  'REBOOT_TO_PROTECTED',
  'RESTORE_BASELINE',
  'INSTALL_RECOVERY_MODULE',
  'REFRESH_STATUS_DETAIL',
  'LIST_EXCEPTION_FOLDERS',
  'ADD_EXCEPTION_FOLDER',
  'REMOVE_EXCEPTION_FOLDER',
] as const
export type ScmateAction = (typeof SCMATE_ACTIONS)[number]
export type ScmateTone = 'protected' | 'operator' | 'transition' | 'unavailable' | 'inactive' | 'license' | 'unknown' | 'error' | 'server'

export const SCMATE_RECOVERY_POINTS: ScmateRecoveryPoint[] = ['BASELINE', 'SNAP1', 'SNAP2']
export const SCMATE_RECOVERY_POINT_LABELS: Record<ScmateRecoveryPoint, string> = {
  BASELINE: '기본 시점',
  SNAP1: '시점 1',
  SNAP2: '시점 2',
}

export const SCMATE_ACTION_LABELS: Record<ScmateAction, string> = {
  REQUEST_OPERATOR: '운영자 모드',
  REBOOT_FOR_BASELINE: '시점 저장 전 재부팅',
  REBOOT_TO_OPERATOR: '재부팅',
  UPDATE_BASELINE: '현재 시점 저장',
  REQUEST_PROTECTED: '매 부팅 복구',
  REBOOT_TO_PROTECTED: '재부팅 후 복구',
  RESTORE_BASELINE: '시스템 복구',
  INSTALL_RECOVERY_MODULE: '복구모듈 설치',
  REFRESH_STATUS_DETAIL: '상세 상태 새로고침',
  LIST_EXCEPTION_FOLDERS: '예외 폴더 조회',
  ADD_EXCEPTION_FOLDER: '예외 폴더 추가',
  REMOVE_EXCEPTION_FOLDER: '예외 폴더 삭제',
}

export interface PcFilters {
  search: string
  groupId: number | 'all'
  state: PcStateFilter
  role: PcRoleFilter
  scmate: ScmateFilter
}

export interface CommandTargetPreview {
  targets: PcStatus[]
  excluded: PcStatus[]
}

type ScmateAvailability = 'available' | 'not-installed' | 'disabled' | 'license-expired' | 'query-unavailable' | 'unknown'

function normalized(value: string | null | undefined) {
  return value?.trim().toUpperCase().replace(/[\s-]+/g, '_') || ''
}

function scmateAvailability(pc: PcStatus): ScmateAvailability {
  const reason = normalized(pc.scmateAvailabilityReason)
  if (/NOT_INSTALLED|UNINSTALLED|MODULE_MISSING|CLIENT_MISSING|MISSING_INSTALL/.test(reason)) return 'not-installed'
  if (/LICENSE.*EXPIRED|EXPIRED.*LICENSE|LICENSE_INVALID|LICENSE_MISSING/.test(reason)) return 'license-expired'
  if (/DISABLED|INACTIVE|PROTECTION_OFF|MODULE_OFF/.test(reason)) return 'disabled'
  if (/QUERY|STATUS_ERROR|STATUS_UNAVAILABLE|UNREACHABLE|READ_FAILED|UNKNOWN/.test(reason)) return 'query-unavailable'
  if (/AVAILABLE|INSTALLED|ACTIVE|READY/.test(reason)) return 'available'
  if (pc.scmateAvailable === true) return 'available'
  // Older servers only returned false for a missing installation.
  if (pc.scmateAvailable === false) return 'not-installed'
  return 'unknown'
}

export function scmateAvailabilityLabel(pc: PcStatus) {
  const availability = scmateAvailability(pc)
  if (availability === 'available') return '설치됨'
  if (availability === 'not-installed') return '설치안됨'
  if (availability === 'disabled') return '비활성'
  if (availability === 'license-expired') return '라이선스 만료'
  if (availability === 'query-unavailable') return '조회 불가'
  return '확인 안 됨'
}

export function scmateDisplayError(pc: PcStatus) {
  const statusError = pc.scmateError?.trim()
  if (statusError) return statusError
  if (scmateAvailability(pc) !== 'query-unavailable') return null
  const reason = pc.scmateAvailabilityReason?.trim()
  if (!reason) return 'SCMATE 상세 상태를 조회하지 못했습니다.'
  const detail = reason.includes(':') ? reason.slice(reason.indexOf(':') + 1).trim() : ''
  return detail || 'SCMATE 상세 상태를 조회하지 못했습니다.'
}

export function scmateState(pc: PcStatus): { tone: ScmateTone; label: string } {
  if (pcRole(pc) === 'teacher') return { tone: 'server', label: '서버' }
  if (pc.scmateError?.trim()) return { tone: 'error', label: 'SCMATE 오류' }
  if (normalized(pc.scmateWorkflowStage) === 'INSTALL_PENDING_REBOOT') {
    return { tone: 'transition', label: '설치 완료 · 재부팅 필요' }
  }
  const availability = scmateAvailability(pc)
  if (availability === 'not-installed') return { tone: 'unavailable', label: '설치안됨' }
  if (availability === 'disabled') return { tone: 'inactive', label: '비활성' }
  if (availability === 'license-expired') return { tone: 'license', label: '라이선스 만료' }
  if (availability === 'query-unavailable') return { tone: 'error', label: '조회 불가' }
  if (scmateDisplayError(pc)) return { tone: 'error', label: 'SCMATE 오류' }
  if (pc.scmateTransitionPending) return { tone: 'transition', label: '전환 중' }
  if (pc.scmateMode === 'OPERATOR') return { tone: 'operator', label: '운영자 모드' }
  if (pc.scmateMode === 'PROTECTED') return { tone: 'protected', label: '매 부팅 복구' }
  return { tone: 'unknown', label: '확인 안 됨' }
}

export function scmateWorkflowStageLabel(value: string | null | undefined) {
  const stage = normalized(value)
  if (!stage) return '-'
  const labels: Record<string, string> = {
    INSTALL_REQUESTED: '복구모듈 설치 요청',
    INSTALL_PENDING_REBOOT: '복구모듈 설치 완료 · 재부팅 필요',
    OPERATOR_REQUESTED: '운영자 모드 전환 요청',
    OPERATOR_READY: '운영자 모드 준비 완료',
    WAITING_REBOOT: '시점 저장 전 재부팅 대기',
    BASELINE_REQUESTED: '시점 저장 요청',
    BASELINE_UPDATED: '시점 저장 완료',
    PROTECTION_REQUESTED: '매 부팅 복구 전환 요청',
    WAITING_PROTECTED_REBOOT: '매 부팅 복구 재부팅 대기',
    RESTORE_REQUESTED: '시스템 복구 요청',
    COMPLETED: '완료',
    CANCELLED: '전달 전 취소됨',
    FAILED: '실패',
  }
  return labels[stage] || value?.trim() || '-'
}

export function allowedScmateActions(pc: PcStatus): ScmateAction[] {
  if (!Array.isArray(pc.scmateAllowedActions)) return []
  const allowed = new Set<string>(SCMATE_ACTIONS)
  return pc.scmateAllowedActions.filter((action): action is ScmateAction => {
    if (!allowed.has(action)) return false
    const availability = scmateAvailability(pc)
    if (action === 'REFRESH_STATUS_DETAIL') return true
    if (action === 'INSTALL_RECOVERY_MODULE') return availability === 'not-installed'
    return availability === 'available'
  })
}

export function scmateActionTargets(pcs: PcStatus[], action: ScmateAction): CommandTargetPreview {
  const targets: PcStatus[] = []
  const excluded: PcStatus[] = []
  for (const pc of pcs) {
    const eligible = pcRole(pc) === 'student'
      && pcState(pc) === 'online'
      && isSelectablePc(pc)
      && allowedScmateActions(pc).includes(action)
    if (eligible) targets.push(pc)
    else excluded.push(pc)
  }
  return { targets, excluded }
}

export function scmateActionConfirmation(action: ScmateAction) {
  if (action === 'INSTALL_RECOVERY_MODULE') return '복구모듈 설치'
  if (action === 'RESTORE_BASELINE') return '시스템 복구'
  if (action === 'UPDATE_BASELINE') return '시점 저장'
  if (action === 'REBOOT_FOR_BASELINE' || action === 'REBOOT_TO_OPERATOR' || action === 'REBOOT_TO_PROTECTED') return '재부팅'
  return undefined
}

export function scmateActionDescription(pc: PcStatus, action: ScmateAction, recoveryPoint: ScmateRecoveryPoint = 'BASELINE') {
  const target = `${pc.groupName || '-'} ${pc.pcName || pc.macAddress}`
  const point = SCMATE_RECOVERY_POINT_LABELS[recoveryPoint]
  const pointDestination = recoveryPoint === 'BASELINE' ? `${point}으로` : `${point}로`
  if (action === 'REQUEST_OPERATOR') return `${target}에 운영자 모드 전환을 요청합니다.`
  if (action === 'REBOOT_FOR_BASELINE') return `${target}의 새 복구 시점을 저장하기 전에 PC를 재부팅합니다. 저장하지 않은 작업이 사라질 수 있습니다.`
  if (action === 'REBOOT_TO_OPERATOR') return `${target}을 운영자 모드로 재부팅합니다. 저장하지 않은 작업이 사라질 수 있습니다.`
  if (action === 'UPDATE_BASELINE') return `${target}의 현재 부팅 상태를 ${pointDestination} 저장합니다. 완료될 때까지 PC 전원을 끄지 마세요.`
  if (action === 'REQUEST_PROTECTED') return `${target}에 ${point} 기준 매 부팅 복구 모드 전환을 요청합니다.`
  if (action === 'INSTALL_RECOVERY_MODULE') return `${target}에 SCMATE 복구모듈을 설치합니다. 설치 중에는 전원을 끄지 마세요. 자동 재부팅은 하지 않습니다.`
  if (action === 'RESTORE_BASELINE') return `${pointDestination} 즉시 복구하며 대상 PC가 재부팅됩니다. ${target}의 저장하지 않은 모든 변경 사항은 삭제됩니다.`
  if (action === 'REFRESH_STATUS_DETAIL') return `${target}의 SCMATE 상세 상태를 새로 조회합니다.`
  return `${target}을 재부팅해 저장된 복구 시점으로 되돌립니다. 저장하지 않은 작업과 현재 변경 사항이 사라집니다.`
}

export function scmateScheduleLabel(value: string | null | undefined) {
  const schedule = normalized(value)
  if (!schedule) return '-'
  if (/OPERATOR|ADMIN|MANUAL|DISABLED/.test(schedule)) return '운영자'
  if (/EVERY_BOOT|EACH_BOOT|ALL_BOOT|ALWAYS|RESTART/.test(schedule)) return '매 부팅'
  if (/DAILY_FIRST_BOOT|FIRST_BOOT_DAILY|DAILY/.test(schedule)) return '매일 첫 부팅'
  if (/WEEK|MON|TUE|WED|THU|FRI|SAT|SUN/.test(schedule)) {
    const dayLabels: Record<string, string> = { MON: '월', TUE: '화', WED: '수', THU: '목', FRI: '금', SAT: '토', SUN: '일' }
    const days = Object.entries(dayLabels).filter(([key]) => schedule.includes(key)).map(([, label]) => label)
    return days.length ? `요일 (${days.join('·')})` : '요일'
  }
  return value?.trim() || '-'
}

export function scmateRecoveryPointLabel(value: string | null | undefined) {
  const point = normalized(value) as ScmateRecoveryPoint
  return SCMATE_RECOVERY_POINT_LABELS[point] || value?.trim() || '-'
}

export function scmateStateSourceLabel(value: string | null | undefined) {
  const source = normalized(value)
  if (!source) return '-'
  if (/DETAIL|CLIENT_STATUS|CLIENT_DISPLAY|LOCAL_CLIENT/.test(source)) return '클라이언트 상세 상태'
  if (/HEARTBEAT|AGENT/.test(source)) return '에이전트 상태'
  if (/SERVER|CACHE/.test(source)) return '서버 캐시'
  if (/DATABASE|LEGACY/.test(source)) return '저장 상태'
  return value?.trim() || '-'
}

const VALID_MAC = /^[0-9a-f]{2}([:-][0-9a-f]{2}){5}$/i

export function pcState(pc: PcStatus): Exclude<PcStateFilter, 'all'> {
  const status = pc.status?.trim().toUpperCase()
  if (status === 'BROKEN' || status === 'FAILED' || pc.macAddress?.toUpperCase().startsWith('BROKEN-')) {
    return 'broken'
  }
  if (pc.online || status === 'ONLINE' || status === 'IDLE') return 'online'
  return 'offline'
}

export function pcStateLabel(pc: PcStatus) {
  const state = pcState(pc)
  if (state === 'online') return '온라인'
  if (state === 'broken') return '고장'
  return '오프라인'
}

export function pcRole(pc: PcStatus): Exclude<PcRoleFilter, 'all'> | 'unknown' {
  const role = pc.role?.trim().toUpperCase()
  if (role === 'TEACHER') return 'teacher'
  if (role === 'STUDENT') return 'student'
  return 'unknown'
}

export function pcRoleLabel(pc: PcStatus) {
  const role = pcRole(pc)
  if (role === 'teacher') return '교사'
  if (role === 'student') return '학생'
  return '미지정'
}

export function isSelectablePc(pc: PcStatus) {
  return VALID_MAC.test(pc.macAddress || '') && pcState(pc) !== 'broken'
}

function includes(value: string | null | undefined, search: string) {
  return value?.toLocaleLowerCase('ko-KR').includes(search) || false
}

export function filterAndSortPcs(pcs: PcStatus[], filters: PcFilters) {
  const search = filters.search.trim().toLocaleLowerCase('ko-KR')
  return pcs
    .filter((pc) => {
      if (filters.groupId !== 'all' && pc.groupId !== filters.groupId) return false
      if (filters.state !== 'all' && pcState(pc) !== filters.state) return false
      if (filters.role !== 'all' && pcRole(pc) !== filters.role) return false
      if (!matchesScmateFilter(pc, filters.scmate)) return false
      if (!search) return true
      return includes(pc.pcName, search)
        || includes(pc.ipAddress, search)
        || includes(pc.macAddress, search)
        || includes(pc.groupName, search)
        || includes(pc.agentVersion, search)
    })
    .sort((left, right) => {
      const group = (left.groupName || '').localeCompare(right.groupName || '', 'ko', { numeric: true })
      if (group !== 0) return group
      const role = Number(pcRole(right) === 'teacher') - Number(pcRole(left) === 'teacher')
      if (role !== 0) return role
      return (left.pcName || '').localeCompare(right.pcName || '', 'ko', { numeric: true })
        || (left.ipAddress || '').localeCompare(right.ipAddress || '', 'en', { numeric: true })
    })
}

export function matchesScmateFilter(pc: PcStatus, filter: ScmateFilter) {
  if (filter === 'all') return true
  if (filter === 'server') return pcRole(pc) === 'teacher'

  const state = scmateState(pc)
  if (filter === 'operator' || filter === 'protected' || filter === 'transition' || filter === 'error' || filter === 'unknown') {
    return state.tone === filter
  }

  const availability = scmateAvailability(pc)
  if (filter === 'not-installed' || filter === 'disabled' || filter === 'license-expired') {
    return availability === filter
  }

  const schedule = normalized(pc.scmateSchedule)
  if (filter === 'restart') return schedule === 'RESTART'
  if (filter === 'daily-first-boot') return schedule === 'DAILY_FIRST_BOOT'
  if (filter === 'weekly') return schedule.includes('WEEK')

  return normalized(pc.scmateSelectedPoint) === filter.toUpperCase()
}

export function commandTargets(pcs: PcStatus[], command: PcPowerCommand): CommandTargetPreview {
  const targets: PcStatus[] = []
  const excluded: PcStatus[] = []
  for (const pc of pcs) {
    const state = pcState(pc)
    const eligible = isSelectablePc(pc) && (command === 'PCON'
      ? state === 'offline' && pc.groupId != null
      : state === 'online')
    if (eligible) targets.push(pc)
    else excluded.push(pc)
  }
  return { targets, excluded }
}

export function selectionState(visiblePcs: PcStatus[], selectedMacs: ReadonlySet<string>) {
  const selectable = visiblePcs.filter(isSelectablePc)
  const selected = selectable.filter((pc) => selectedMacs.has(pc.macAddress)).length
  return {
    selectableMacs: selectable.map((pc) => pc.macAddress),
    selected,
    checked: selectable.length > 0 && selected === selectable.length,
    indeterminate: selected > 0 && selected < selectable.length,
  }
}

export function commandLabel(command: PcPowerCommand) {
  if (command === 'PCON') return 'PC 켜기'
  if (command === 'REBOOT') return '재시작'
  return '종료'
}
