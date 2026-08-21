import { describe, expect, it } from 'vitest'
import type { PcStatus } from '../../types/dashboard'
import {
  allowedScmateActions,
  commandTargets,
  filterAndSortPcs,
  matchesScmateFilter,
  scmateActionDescription,
  scmateActionConfirmation,
  scmateActionTargets,
  scmateAvailabilityLabel,
  scmateDisplayError,
  scmateRecoveryPointLabel,
  scmateScheduleLabel,
  scmateState,
  scmateStateSourceLabel,
  scmateWorkflowStageLabel,
  SCMATE_ACTION_LABELS,
  pcState,
  selectionState,
} from './model'

function pc(overrides: Partial<PcStatus> = {}): PcStatus {
  return {
    macAddress: '00:11:22:33:44:55',
    pcName: 'PC-1',
    groupId: 218,
    groupName: '218호',
    online: false,
    role: 'STUDENT',
    ...overrides,
  }
}

describe('PC management model', () => {
  it('normalizes online and broken states before filtering', () => {
    expect(pcState(pc({ online: false, status: 'IDLE' }))).toBe('online')
    expect(pcState(pc({ online: true, status: 'BROKEN' }))).toBe('broken')
    expect(pcState(pc({ macAddress: 'BROKEN-218-9' }))).toBe('broken')
  })

  it('filters all searchable fields and sorts teacher then numeric PC names', () => {
    const pcs = [
      pc({ macAddress: '00:00:00:00:00:10', pcName: 'PC-10', agentVersion: '1.1.12' }),
      pc({ macAddress: '00:00:00:00:00:02', pcName: 'PC-2', agentVersion: '1.1.12' }),
      pc({ macAddress: '00:00:00:00:00:01', pcName: 'TEACHER', role: 'TEACHER', agentVersion: '1.1.12' }),
      pc({ macAddress: '00:00:00:00:01:01', pcName: 'PC-1', groupId: 217, groupName: '217호', agentVersion: '1.1.11' }),
    ]
    const result = filterAndSortPcs(pcs, { search: '1.1.12', groupId: 218, state: 'all', role: 'all', scmate: 'all' })
    expect(result.map((item) => item.pcName)).toEqual(['TEACHER', 'PC-2', 'PC-10'])
  })

  it('filters SCMATE mode, schedule, recovery point and exceptional states', () => {
    const operator = pc({ scmateAvailable: true, scmateMode: 'OPERATOR', scmateSchedule: 'MANUAL' })
    const protectedPc = pc({
      macAddress: '00:11:22:33:44:56',
      scmateAvailable: true,
      scmateMode: 'PROTECTED',
      scmateSchedule: 'RESTART',
      scmateSelectedPoint: 'SNAP1',
    })
    const notInstalled = pc({
      macAddress: '00:11:22:33:44:57',
      scmateAvailable: false,
      scmateAvailabilityReason: 'NOT_INSTALLED',
    })

    expect(matchesScmateFilter(operator, 'operator')).toBe(true)
    expect(matchesScmateFilter(protectedPc, 'protected')).toBe(true)
    expect(matchesScmateFilter(protectedPc, 'restart')).toBe(true)
    expect(matchesScmateFilter(protectedPc, 'snap1')).toBe(true)
    expect(matchesScmateFilter(notInstalled, 'not-installed')).toBe(true)
    expect(filterAndSortPcs([operator, protectedPc, notInstalled], {
      search: '', groupId: 'all', state: 'all', role: 'all', scmate: 'restart',
    })).toEqual([protectedPc])
  })

  it('sends shutdown and reboot only to online healthy PCs', () => {
    const online = pc({ online: true })
    const offline = pc({ macAddress: '00:11:22:33:44:56' })
    const broken = pc({ macAddress: 'BROKEN-218-9', status: 'BROKEN' })
    expect(commandTargets([online, offline, broken], 'SHUTDOWN')).toEqual({
      targets: [online],
      excluded: [offline, broken],
    })
    expect(commandTargets([online, offline, broken], 'REBOOT').targets).toEqual([online])
  })

  it('sends power-on only to grouped offline PCs with a valid MAC', () => {
    const offline = pc()
    const online = pc({ macAddress: '00:11:22:33:44:56', online: true })
    const ungrouped = pc({ macAddress: '00:11:22:33:44:57', groupId: null })
    expect(commandTargets([offline, online, ungrouped], 'PCON')).toEqual({
      targets: [offline],
      excluded: [online, ungrouped],
    })
  })

  it('calculates checked and indeterminate state from selectable filtered PCs', () => {
    const first = pc()
    const second = pc({ macAddress: '00:11:22:33:44:56' })
    const broken = pc({ macAddress: 'BROKEN-218-9', status: 'BROKEN' })
    const state = selectionState([first, second, broken], new Set([first.macAddress]))
    expect(state).toMatchObject({ selected: 1, checked: false, indeterminate: true })
    expect(state.selectableMacs).toEqual([first.macAddress, second.macAddress])
  })

  it('maps SCMATE state with error and transition priority', () => {
    expect(scmateState(pc({ scmateAvailable: false }))).toEqual({ tone: 'unavailable', label: '설치안됨' })
    expect(scmateState(pc({ scmateAvailable: false, scmateError: 'status read failed' }))).toEqual({ tone: 'error', label: 'SCMATE 오류' })
    expect(scmateState(pc({ scmateAvailable: true, scmateMode: 'OPERATOR' }))).toEqual({ tone: 'operator', label: '운영자 모드' })
    expect(scmateState(pc({ scmateAvailable: true, scmateMode: 'PROTECTED' }))).toEqual({ tone: 'protected', label: '매 부팅 복구' })
    expect(scmateState(pc({ scmateAvailable: true, scmateMode: 'OPERATOR', scmateTransitionPending: true }))).toEqual({ tone: 'transition', label: '전환 중' })
    expect(scmateState(pc({ scmateAvailable: true, scmateTransitionPending: true, scmateError: 'failed' }))).toEqual({ tone: 'error', label: 'SCMATE 오류' })
    expect(scmateState(pc({ scmateAvailable: false, scmateAvailabilityReason: 'DISABLED' }))).toEqual({ tone: 'inactive', label: '비활성' })
    expect(scmateState(pc({ scmateAvailable: false, scmateAvailabilityReason: 'LICENSE_EXPIRED' }))).toEqual({ tone: 'license', label: '라이선스 만료' })
    expect(scmateState(pc({ scmateAvailable: false, scmateAvailabilityReason: 'STATUS_ERROR: raw read failed' }))).toEqual({ tone: 'error', label: '조회 불가' })
    expect(scmateState(pc({ role: 'TEACHER', scmateAvailable: false }))).toEqual({ tone: 'server', label: '서버' })
    expect(scmateState(pc({
      scmateAvailable: false,
      scmateAvailabilityReason: 'NOT_INSTALLED',
      scmateWorkflowStage: 'INSTALL_PENDING_REBOOT',
    }))).toEqual({ tone: 'transition', label: '설치 완료 · 재부팅 필요' })
  })

  it('shows operator-friendly SCMATE workflow stage labels', () => {
    expect(scmateWorkflowStageLabel('INSTALL_PENDING_REBOOT')).toBe('복구모듈 설치 완료 · 재부팅 필요')
    expect(scmateWorkflowStageLabel('CANCELLED')).toBe('전달 전 취소됨')
    expect(scmateWorkflowStageLabel('FUTURE_STAGE')).toBe('FUTURE_STAGE')
  })

  it('formats detailed SCMATE state and gives the status error priority', () => {
    const target = pc({
      scmateAvailable: false,
      scmateAvailabilityReason: 'STATUS_ERROR: raw read failed',
      scmateError: '상세 상태 인증에 실패했습니다.',
    })
    expect(scmateAvailabilityLabel(target)).toBe('조회 불가')
    expect(scmateDisplayError(target)).toBe('상세 상태 인증에 실패했습니다.')
    expect(scmateScheduleLabel('RESTART')).toBe('매 부팅')
    expect(scmateScheduleLabel('DAILY_FIRST_BOOT')).toBe('매일 첫 부팅')
    expect(scmateScheduleLabel('WEEKLY_MON_WED_FRI')).toBe('요일 (월·수·금)')
    expect(scmateRecoveryPointLabel('SNAP2')).toBe('시점 2')
    expect(scmateStateSourceLabel('CLIENT_STATUS_DETAIL')).toBe('클라이언트 상세 상태')
    expect(scmateStateSourceLabel('SCMATE_CLIENT_DISPLAY')).toBe('클라이언트 상세 상태')
  })

  it('keeps only backend-allowed actions from the fixed SCMATE contract', () => {
    const target = pc({
      scmateAvailable: true,
      scmateAllowedActions: ['REQUEST_OPERATOR', 'UPDATE_BASELINE', 'RESTORE_BASELINE', 'REFRESH_STATUS_DETAIL', 'UNSUPPORTED'],
    })
    expect(allowedScmateActions(target)).toEqual(['REQUEST_OPERATOR', 'UPDATE_BASELINE', 'RESTORE_BASELINE', 'REFRESH_STATUS_DETAIL'])
    expect(allowedScmateActions(pc({ scmateAvailable: false, scmateAllowedActions: ['REQUEST_OPERATOR'] }))).toEqual([])
    expect(allowedScmateActions(pc({ scmateAvailable: false, scmateAllowedActions: ['INSTALL_RECOVERY_MODULE'] })))
      .toEqual(['INSTALL_RECOVERY_MODULE'])
    expect(scmateActionConfirmation('UPDATE_BASELINE')).toBe('시점 저장')
    expect(scmateActionConfirmation('RESTORE_BASELINE')).toBe('시스템 복구')
    expect(scmateActionDescription(target, 'RESTORE_BASELINE')).toContain('기본 시점으로 즉시 복구하며 대상 PC가 재부팅됩니다.')
    expect(scmateActionDescription(target, 'RESTORE_BASELINE', 'SNAP1')).toContain('시점 1로 즉시 복구')
    expect(scmateActionDescription(target, 'RESTORE_BASELINE')).toContain('저장하지 않은 모든 변경 사항은 삭제됩니다.')
    expect(scmateActionConfirmation('REBOOT_FOR_BASELINE')).toBe('재부팅')
    expect(scmateActionConfirmation('INSTALL_RECOVERY_MODULE')).toBe('복구모듈 설치')
    expect(scmateActionDescription(target, 'REBOOT_FOR_BASELINE')).toContain('새 복구 시점을 저장하기 전에 PC를 재부팅합니다.')
    expect(scmateActionConfirmation('REBOOT_TO_OPERATOR')).toBe('재부팅')
    expect(SCMATE_ACTION_LABELS.REBOOT_TO_PROTECTED).toBe('재부팅 후 복구')
    expect(SCMATE_ACTION_LABELS.REFRESH_STATUS_DETAIL).toBe('상세 상태 새로고침')
    expect(scmateActionConfirmation('REQUEST_OPERATOR')).toBeUndefined()
  })

  it('targets only online healthy student PCs that allow the classroom SCMATE action', () => {
    const eligible = pc({ online: true, role: 'STUDENT', scmateAvailable: true, scmateAllowedActions: ['REQUEST_OPERATOR'] })
    const teacher = pc({ macAddress: '00:11:22:33:44:57', online: true, role: 'TEACHER', scmateAvailable: false, scmateAllowedActions: [] })
    const offline = pc({ macAddress: '00:11:22:33:44:58', role: 'STUDENT', scmateAvailable: true, scmateAllowedActions: ['REQUEST_OPERATOR'] })
    const wrongState = pc({ macAddress: '00:11:22:33:44:59', online: true, role: 'STUDENT', scmateAvailable: true, scmateAllowedActions: ['UPDATE_BASELINE'] })

    expect(scmateActionTargets([eligible, teacher, offline, wrongState], 'REQUEST_OPERATOR')).toEqual({
      targets: [eligible],
      excluded: [teacher, offline, wrongState],
    })
  })
})
