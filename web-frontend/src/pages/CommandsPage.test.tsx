import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CommandsPage } from './CommandsPage'
import type { CommandPage, CommandView } from '../features/commands/types'

const mocks = vi.hoisted(() => ({
  isAdmin: true,
  getCommands: vi.fn(),
  getCommand: vi.fn(),
  issueCommand: vi.fn(),
  cancelCommand: vi.fn(),
  showToast: vi.fn(),
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ isAdmin: mocks.isAdmin }),
}))

vi.mock('../components/ui/ToastProvider', () => ({
  useToast: () => ({ showToast: mocks.showToast }),
}))

vi.mock('../features/commands/api', () => ({
  getCommands: mocks.getCommands,
  getCommand: mocks.getCommand,
  issueCommand: mocks.issueCommand,
  cancelCommand: mocks.cancelCommand,
}))

function command(overrides: Partial<CommandView> = {}): CommandView {
  return {
    taskId: 7,
    macAddress: 'AA:BB:CC:DD:EE:FF',
    pcName: '218-01',
    groupName: '218호',
    commandType: 'SHUTDOWN',
    status: 'COMPLETED',
    createdAt: '2026-08-07T17:00:00',
    exitCode: 0,
    ...overrides,
  }
}

function page(items: CommandView[]): CommandPage {
  return {
    content: items,
    totalElements: items.length,
    totalPages: items.length ? 1 : 0,
    size: 20,
    number: 0,
    first: true,
    last: true,
    numberOfElements: items.length,
    empty: items.length === 0,
  }
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><CommandsPage /></QueryClientProvider>)
}

describe('CommandsPage', () => {
  afterEach(cleanup)

  beforeEach(() => {
    mocks.isAdmin = true
    mocks.getCommands.mockReset().mockResolvedValue(page([command()]))
    mocks.getCommand.mockReset().mockResolvedValue(command({ stdout: 'shutdown accepted', stderr: 'no error' }))
    mocks.issueCommand.mockReset().mockResolvedValue({ queued: 1, taskIds: [8] })
    mocks.cancelCommand.mockReset().mockResolvedValue({ taskId: 7, status: 'CANCELLED' })
    mocks.showToast.mockReset()
  })

  it('blocks command history and mutation controls for non-admin users', () => {
    mocks.isAdmin = false
    renderPage()
    expect(screen.getByText('명령 이력과 발행은 관리자만 사용할 수 있습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '명령 발행' })).not.toBeInTheDocument()
    expect(mocks.getCommands).not.toHaveBeenCalled()
  })

  it('opens a refreshed detail view with stdout and stderr', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByText('#7'))
    expect(await screen.findByText('shutdown accepted')).toBeInTheDocument()
    expect(screen.getByText('no error')).toBeInTheDocument()
    expect(mocks.getCommand).toHaveBeenCalledWith(7)
  })

  it('loads an explicit 500-command snapshot and filters it without pretending to query the server', async () => {
    const user = userEvent.setup()
    mocks.getCommands.mockResolvedValue(page([
      command(),
      command({ taskId: 8, pcName: '210-02', groupName: '210호', commandType: 'REBOOT' }),
    ]))
    renderPage()

    expect(await screen.findByText('#7')).toBeInTheDocument()
    expect(mocks.getCommands).toHaveBeenCalledWith(expect.objectContaining({ page: 0, size: 500 }))
    await user.type(screen.getByLabelText('PC·MAC·강의실 검색'), '210-02')
    expect(screen.queryByText('#7')).not.toBeInTheDocument()
    expect(screen.getByText('#8')).toBeInTheDocument()
    expect(mocks.getCommands).toHaveBeenCalledTimes(1)
  })

  it('keeps the current snapshot and selected detail visible after a refresh error', async () => {
    const user = userEvent.setup()
    mocks.getCommands.mockResolvedValueOnce(page([command()])).mockRejectedValueOnce(new Error('temporary outage'))
    renderPage()
    await user.click(await screen.findByText('#7'))
    expect(await screen.findByRole('complementary', { name: '명령 상세' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '새로고침' }))
    expect(await screen.findByText('자동 갱신에 실패했습니다. 기존 스냅샷을 유지합니다.')).toBeInTheDocument()
    expect(screen.getByText('#7')).toBeInTheDocument()
    expect(screen.getByRole('complementary', { name: '명령 상세' })).toBeInTheDocument()
  })

  it('issues an admin command only after form validation and confirmation', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: '명령 발행' }))
    await user.type(screen.getByPlaceholderText('AA:BB:CC:DD:EE:FF'), 'AA:BB:CC:DD:EE:FF')
    await user.click(screen.getByRole('button', { name: '확인' }))
    const confirmDialog = await screen.findByRole('alertdialog')
    const confirm = within(confirmDialog).getByRole('button', { name: '명령 발행' })
    await user.dblClick(confirm)
    expect(mocks.issueCommand).toHaveBeenCalledTimes(1)
    expect(mocks.issueCommand.mock.calls[0]?.[0]).toEqual({
      scope: 'SINGLE',
      type: 'SHUTDOWN',
      macAddress: 'AA:BB:CC:DD:EE:FF',
    })
  })

  it('cancels a queued command from its history detail after confirmation', async () => {
    const user = userEvent.setup()
    const queued = command({ status: 'QUEUED', exitCode: undefined })
    mocks.getCommands.mockResolvedValue(page([queued]))
    mocks.getCommand.mockResolvedValue(queued)
    renderPage()

    await user.click(await screen.findByText('#7'))
    await user.click(await screen.findByRole('button', { name: '대기 명령 취소' }))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: '명령 취소' }))

    expect(mocks.cancelCommand).toHaveBeenCalledWith(7)
  })
})
