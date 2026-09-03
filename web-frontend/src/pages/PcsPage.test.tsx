import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PcsPage } from './PcsPage'

const mocks = vi.hoisted(() => ({
  getPcs: vi.fn(),
  showToast: vi.fn(),
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ hasPermission: () => true }),
}))

vi.mock('../components/ui/ToastProvider', () => ({
  useToast: () => ({ showToast: mocks.showToast }),
}))

vi.mock('../features/pcs/api', () => ({
  getPcs: mocks.getPcs,
  sendPcCommands: vi.fn(),
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
    mocks.getPcs.mockReset().mockResolvedValue([{
      groupId: 218,
      groupName: '218호',
      macAddress: 'AA:BB:CC:DD:EE:FF',
      pcName: '218-01',
      ipAddress: '192.168.10.11',
      online: true,
      status: 'ONLINE',
      role: 'STUDENT',
    }])
    mocks.showToast.mockReset()
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
})
