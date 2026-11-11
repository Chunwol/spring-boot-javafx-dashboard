import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PcsPage } from './PcsPage'

const mocks = vi.hoisted(() => ({
  getPcs: vi.fn(),
  hasPermission: vi.fn(),
  showToast: vi.fn(),
  updateDeviceNote: vi.fn(),
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ hasPermission: mocks.hasPermission }),
}))

vi.mock('../components/ui/ToastProvider', () => ({
  useToast: () => ({ showToast: mocks.showToast }),
}))

vi.mock('../features/pcs/api', () => ({
  getPcs: mocks.getPcs,
  sendPcCommands: vi.fn(),
  updateDeviceNote: mocks.updateDeviceNote,
}))

vi.mock('../features/remoteControl/RemoteControlDialog', () => ({
  RemoteControlDialog: ({ pc, mode = 'control', onClose }: {
    pc: { pcName?: string }
    mode?: 'control' | 'view'
    onClose: () => void
  }) => (
    <div data-testid="remote-dialog">
      <span>{pc.pcName}:{mode}</span>
      <button onClick={onClose}>닫기</button>
    </div>
  ),
}))

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><PcsPage /></MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PcsPage individual screen viewer', () => {
  afterEach(cleanup)

  beforeEach(() => {
    mocks.hasPermission.mockReset().mockReturnValue(true)
    mocks.getPcs.mockReset().mockResolvedValue([{
      groupId: 218,
      groupName: '218호',
      macAddress: 'AA:BB:CC:DD:EE:FF',
      pcName: '218-01',
      ipAddress: '192.168.10.11',
      online: true,
      status: 'ONLINE',
      role: 'STUDENT',
      deviceNote: '사용 가능·점검 필요',
    }])
    mocks.showToast.mockReset()
    mocks.updateDeviceNote.mockReset().mockResolvedValue({
      macAddress: 'AA:BB:CC:DD:EE:FF',
      pcName: '218-01',
      deviceNote: '새 메모',
    })
  })

  it('opens view-only viewer separately from remote control', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '218-01 화면 보기' }))
    expect(screen.getByTestId('remote-dialog')).toHaveTextContent('218-01:view')

    await user.click(screen.getByRole('button', { name: '닫기' }))
    await user.click(screen.getByRole('button', { name: '218-01 원격 제어' }))
    expect(screen.getByTestId('remote-dialog')).toHaveTextContent('218-01:control')
  })

  it('shows a device note without disabling online operations', async () => {
    renderPage()

    expect(await screen.findByText('사용 가능·점검 필요')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '218-01 작업' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '218-01 장치 메모 편집' })).toBeEnabled()
  })

  it('edits and trims a device note for the selected PC', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '218-01 장치 메모 편집' }))
    const input = screen.getByRole('textbox', { name: '장치 메모' })
    await user.clear(input)
    await user.type(input, '  새 메모  ')
    await user.click(screen.getByRole('button', { name: '메모 저장' }))

    await waitFor(() => expect(mocks.updateDeviceNote).toHaveBeenCalledWith('AA:BB:CC:DD:EE:FF', '새 메모'))
    expect(mocks.showToast).toHaveBeenCalledWith('218-01 장치 메모를 저장했습니다.')
  })

  it('clears an existing device note', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: '218-01 장치 메모 편집' }))
    await user.click(screen.getByRole('button', { name: '메모 삭제' }))

    await waitFor(() => expect(mocks.updateDeviceNote).toHaveBeenCalledWith('AA:BB:CC:DD:EE:FF', null))
  })

  it('keeps the note readable when edit permission is missing', async () => {
    mocks.hasPermission.mockImplementation((permission: string) => permission !== 'BROKEN_PC_REGISTER_EDIT')
    renderPage()

    expect(await screen.findByText('사용 가능·점검 필요')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '218-01 장치 메모 편집' })).not.toBeInTheDocument()
  })
})
