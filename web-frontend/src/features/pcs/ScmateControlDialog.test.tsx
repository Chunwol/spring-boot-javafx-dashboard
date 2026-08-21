import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../../components/ui/ToastProvider'
import type { PcStatus } from '../../types/dashboard'
import { sendScmateAction, sendScmateActions } from './api'
import { ScmateControlDialog } from './ScmateControlDialog'

vi.mock('./api', () => ({ sendScmateAction: vi.fn(), sendScmateActions: vi.fn() }))

const mockedSendScmateAction = vi.mocked(sendScmateAction)
const mockedSendScmateActions = vi.mocked(sendScmateActions)

function pc(overrides: Partial<PcStatus> = {}): PcStatus {
  return {
    macAddress: 'AA:BB:CC:DD:EE:01',
    pcName: '314-01',
    groupName: '314호',
    online: true,
    scmateAvailable: true,
    scmateMode: 'OPERATOR',
    scmateAllowedActions: ['UPDATE_BASELINE', 'UNSUPPORTED'],
    ...overrides,
  }
}

function renderDialog(target = pc(), classroomPcs?: PcStatus[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ToastProvider><ScmateControlDialog pc={target} classroomPcs={classroomPcs} canAct onClose={vi.fn()} /></ToastProvider>
    </QueryClientProvider>,
  )
}

describe('ScmateControlDialog', () => {
  afterEach(() => {
    cleanup()
    mockedSendScmateAction.mockReset()
    mockedSendScmateActions.mockReset()
  })

  it('shows only allowed contract actions and requires text before baseline update', async () => {
    const user = userEvent.setup()
    mockedSendScmateAction.mockResolvedValue({ message: 'accepted' })
    renderDialog()

    expect(screen.getByText('운영자 모드')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '현재 시점 저장' })).toBeInTheDocument()
    expect(screen.getByText('기본 시점 생성')).toBeInTheDocument()
    expect(screen.queryByText('UNSUPPORTED')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '현재 시점 저장' }))
    const confirm = screen.getByRole('alertdialog')
    const submit = within(confirm).getByRole('button', { name: '현재 시점 저장' })
    expect(submit).toBeDisabled()

    await user.type(within(confirm).getByRole('textbox'), '시점 저장')
    await user.click(submit)

    expect(mockedSendScmateAction).toHaveBeenCalledWith('AA:BB:CC:DD:EE:01', 'UPDATE_BASELINE', 'BASELINE')
  })

  it('shows the last successfully saved baseline time', () => {
    renderDialog(pc({ scmateBaselineCreatedAt: '2026-08-12T21:34:56' }))

    expect(screen.getByText('기본 시점 생성')).toBeInTheDocument()
    expect(screen.getByText(/2026/)).toBeInTheDocument()
  })

  it('shows a clear reboot-required state after recovery module installation', () => {
    renderDialog(pc({
      scmateAvailable: false,
      scmateAvailabilityReason: 'NOT_INSTALLED',
      scmateWorkflowStage: 'INSTALL_PENDING_REBOOT',
      scmateAllowedActions: ['REBOOT_TO_OPERATOR'],
    }))

    expect(screen.getByText('설치 완료 · 재부팅 필요')).toBeInTheDocument()
    expect(screen.getByText('복구모듈 설치 완료 · 재부팅 필요')).toBeInTheDocument()
  })

  it('uses the SCMate manual wording and typed confirmation for system restore', async () => {
    const user = userEvent.setup()
    mockedSendScmateAction.mockResolvedValue({ message: 'accepted' })
    renderDialog(pc({
      scmateMode: 'PROTECTED',
      scmateAllowedActions: ['RESTORE_BASELINE'],
    }))

    await user.click(screen.getByRole('button', { name: '시스템 복구' }))
    const confirm = screen.getByRole('alertdialog')
    expect(confirm).toHaveTextContent('기본 시점으로 즉시 복구하며 대상 PC가 재부팅됩니다.')
    expect(confirm).toHaveTextContent('저장하지 않은 모든 변경 사항은 삭제됩니다.')
    const submit = within(confirm).getByRole('button', { name: '시스템 복구' })
    expect(submit).toBeDisabled()

    await user.type(within(confirm).getByRole('textbox'), '시스템 복구')
    await user.click(submit)

    expect(mockedSendScmateAction).toHaveBeenCalledWith('AA:BB:CC:DD:EE:01', 'RESTORE_BASELINE', 'BASELINE')
  })

  it('reuses the SCMATE modal for a teacher PC and targets eligible students in its classroom', async () => {
    const user = userEvent.setup()
    mockedSendScmateActions.mockResolvedValue({ requested: 1, queued: 1, failed: 0, errors: [] })
    const teacher = pc({ role: 'TEACHER', pcName: '314-T', scmateAvailable: false, scmateAllowedActions: [] })
    const student = pc({ role: 'STUDENT', pcName: '314-01', scmateAllowedActions: ['REQUEST_OPERATOR'] })
    const offline = pc({ role: 'STUDENT', pcName: '314-02', online: false, scmateAllowedActions: ['REQUEST_OPERATOR'] })
    renderDialog(teacher, [teacher, student, offline])

    expect(screen.getByRole('heading', { name: '강의실 SCMATE' })).toBeInTheDocument()
    expect(screen.getByText('서버')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /운영자 모드/ }))
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '운영자 모드' }))

    expect(mockedSendScmateActions).toHaveBeenCalledWith([student], 'REQUEST_OPERATOR', 'BASELINE')
    expect(mockedSendScmateAction).not.toHaveBeenCalled()
  })

  it('shows detailed status fields and refreshes them without confirmation', async () => {
    const user = userEvent.setup()
    mockedSendScmateAction.mockResolvedValue({ message: 'accepted' })
    renderDialog(pc({
      scmateAvailable: false,
      scmateAvailabilityReason: 'LICENSE_EXPIRED',
      scmateError: '상세 상태 오류',
      scmateSchedule: 'WEEKLY_MON_WED',
      scmateSelectedPoint: 'SNAP1',
      scmateProductVariant: 'RX2 Client',
      scmateStateSource: 'CLIENT_STATUS_DETAIL',
      scmateBaselineCreatedAt: '2026-08-13T10:00:00',
      scmateSnap1CreatedAt: '2026-08-13T11:00:00',
      systemBootTimeEpochSeconds: 1_786_590_000,
      scmateAllowedActions: ['REFRESH_STATUS_DETAIL'],
    }))

    expect(screen.getByText('SCMATE 오류')).toBeInTheDocument()
    expect(screen.getByText('라이선스 만료')).toBeInTheDocument()
    expect(screen.getByText('요일 (월·수)')).toBeInTheDocument()
    expect(screen.getByText('시점 1')).toBeInTheDocument()
    expect(screen.getByText('RX2 Client')).toBeInTheDocument()
    expect(screen.getByText('클라이언트 상세 상태')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('상세 상태 오류')

    await user.click(screen.getByRole('button', { name: '상세 상태 새로고침' }))
    expect(mockedSendScmateAction).toHaveBeenCalledWith('AA:BB:CC:DD:EE:01', 'REFRESH_STATUS_DETAIL', 'BASELINE')
  })

  it('uses the selected recovery point for individual and classroom actions', async () => {
    const user = userEvent.setup()
    mockedSendScmateAction.mockResolvedValue({ message: 'accepted' })
    renderDialog(pc({ scmateAllowedActions: ['UPDATE_BASELINE'] }))

    await user.click(screen.getByRole('radio', { name: '시점 2' }))
    await user.click(screen.getByRole('button', { name: '현재 시점 저장' }))
    const confirm = screen.getByRole('alertdialog')
    expect(confirm).toHaveTextContent('시점 2')
    await user.type(within(confirm).getByRole('textbox'), '시점 저장')
    await user.click(within(confirm).getByRole('button', { name: '현재 시점 저장' }))

    expect(mockedSendScmateAction).toHaveBeenCalledWith('AA:BB:CC:DD:EE:01', 'UPDATE_BASELINE', 'SNAP2')
  })

  it('keeps classroom API error messages visible after a partial failure', async () => {
    const user = userEvent.setup()
    mockedSendScmateActions.mockResolvedValue({
      requested: 1,
      queued: 0,
      failed: 1,
      errors: [{ pcName: '314-01', message: '라이선스가 만료되었습니다.' }],
    })
    const teacher = pc({ role: 'TEACHER', pcName: '314-T', scmateAvailable: false, scmateAllowedActions: [] })
    const student = pc({ role: 'STUDENT', pcName: '314-01', scmateAllowedActions: ['REQUEST_OPERATOR'] })
    renderDialog(teacher, [teacher, student])

    await user.click(screen.getByRole('button', { name: /운영자 모드/ }))
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '운영자 모드' }))

    expect(screen.getByText('전송 실패 1대')).toBeInTheDocument()
    expect(screen.getByText('314-01')).toBeInTheDocument()
    expect(screen.getByText('라이선스가 만료되었습니다.')).toBeInTheDocument()
  })
})
