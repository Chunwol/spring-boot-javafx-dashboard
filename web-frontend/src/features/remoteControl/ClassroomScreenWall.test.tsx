import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ClassroomScreenWall } from './ClassroomScreenWall'
import { SCREEN_WALL_RECONNECT_DELAY_MS } from './screenWall'

const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  get: vi.fn(),
  ticket: vi.fn(),
  close: vi.fn(),
  closeBatchOnExit: vi.fn(),
  disconnect: vi.fn(),
  rfbCreated: vi.fn(),
}))

vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ token: 'test-jwt' }),
}))

vi.mock('./api', () => ({
  createRemoteControlSession: mocks.create,
  getRemoteControlSession: mocks.get,
  issueRemoteControlTicket: mocks.ticket,
  closeRemoteControlSession: mocks.close,
  closeRemoteControlSessionsOnPageExit: mocks.closeBatchOnExit,
  remoteControlWebSocketUrl: () => 'ws://localhost/vnc-relay/session?ticket=secret',
}))

vi.mock('@novnc/novnc', () => ({
  default: function MockRfb() {
    const listeners = new Map<string, (event: CustomEvent) => void>()
    const client = {
      viewOnly: false,
      scaleViewport: false,
      resizeSession: true,
      focusOnClick: true,
      qualityLevel: 7,
      compressionLevel: 3,
      disconnect: mocks.disconnect,
      sendCredentials: vi.fn(),
      addEventListener(type: string, listener: (event: CustomEvent) => void) {
        listeners.set(type, listener)
        if (type === 'connect') queueMicrotask(() => listener(new CustomEvent('connect')))
      },
    }
    mocks.rfbCreated(client, listeners)
    return client
  },
}))

const room = {
  groupId: 214,
  groupName: '214호',
  onlinePcs: 1,
  totalPcs: 1,
  runningCommandCount: 0,
}

const pc = {
  macAddress: 'AA:BB:CC:DD:EE:FF',
  pcName: '214-1',
  ipAddress: '223.194.167.61',
  groupId: 214,
  groupName: '214호',
  online: true,
  role: 'STUDENT',
}

const teacherPc = {
  ...pc,
  macAddress: 'AA:BB:CC:DD:EE:01',
  pcName: '214_T',
  ipAddress: '223.194.167.60',
  role: 'TEACHER',
}

describe('ClassroomScreenWall', () => {
  afterEach(cleanup)

  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset())
    mocks.create.mockResolvedValue({
      sessionId: 'wall-session', taskId: 1, macAddress: pc.macAddress,
      status: 'READY', expiresAt: '2026-08-09T20:00:00Z',
    })
    mocks.ticket.mockResolvedValue({
      sessionId: 'wall-session', taskId: 1,
      webSocketPath: '/vnc-relay/wall-session?ticket=secret',
      oneTimePassword: 'Abcd1234', ticketExpiresAt: '2026-08-09T19:30:00Z',
    })
    mocks.close.mockResolvedValue(undefined)
  })

  it('opens a low-bandwidth view-only stream and cleans it up', async () => {
    const view = render(<ClassroomScreenWall room={room} pcs={[pc]} onClose={vi.fn()} />)

    expect(await screen.findByText('연결됨')).toBeInTheDocument()
    const client = mocks.rfbCreated.mock.calls[0][0]
    expect(client.viewOnly).toBe(true)
    expect(client.scaleViewport).toBe(true)
    expect(client.resizeSession).toBe(false)
    expect(client.focusOnClick).toBe(false)
    expect(client.qualityLevel).toBe(2)
    expect(client.compressionLevel).toBe(9)

    window.dispatchEvent(new Event('pagehide'))
    expect(mocks.closeBatchOnExit).toHaveBeenCalledWith(['wall-session'], 'test-jwt')

    view.unmount()
    await waitFor(() => expect(mocks.close).toHaveBeenCalledWith('wall-session'))
    expect(mocks.disconnect).toHaveBeenCalled()
  })

  it('does not reconnect when a status refresh replaces the PC object', async () => {
    const view = render(<ClassroomScreenWall room={room} pcs={[pc]} onClose={vi.fn()} />)
    expect(await screen.findByText('연결됨')).toBeInTheDocument()

    view.rerender(<ClassroomScreenWall room={{ ...room }} pcs={[{ ...pc }]} onClose={vi.fn()} />)

    await waitFor(() => expect(mocks.create).toHaveBeenCalledTimes(1))
    expect(mocks.rfbCreated).toHaveBeenCalledTimes(1)
  })

  it('includes the teacher PC as the first view-only monitoring tile', async () => {
    render(<ClassroomScreenWall room={room} pcs={[pc, teacherPc]} onClose={vi.fn()} />)

    expect(await screen.findByText('교사 PC · 214_T')).toBeInTheDocument()
    expect(screen.getByText('교사 PC 1대 · 학생 PC 1대 · 연결 2/2')).toBeInTheDocument()
    await waitFor(() => expect(mocks.create).toHaveBeenCalledTimes(2))
    expect(mocks.create.mock.calls.map(([macAddress]) => macAddress)).toContain(teacherPc.macAddress)
    expect(mocks.rfbCreated.mock.calls.every(([client]) => client.viewOnly)).toBe(true)
  })

  it('switches between teacher, student, and combined monitoring targets', async () => {
    render(<ClassroomScreenWall room={room} pcs={[pc, teacherPc]} onClose={vi.fn()} />)
    expect(await screen.findByText('교사 PC 1대 · 학생 PC 1대 · 연결 2/2')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '교사 PC' }))

    expect(await screen.findByText('교사 PC 1대 · 학생 PC 0대 · 연결 1/1')).toBeInTheDocument()
    expect(screen.queryByText('1번 · 214-1')).not.toBeInTheDocument()
    expect(screen.getByText('교사 PC · 214_T')).toBeInTheDocument()
  })

  it('automatically recreates a student screen session after an unexpected disconnect', async () => {
    render(<ClassroomScreenWall room={room} pcs={[pc]} onClose={vi.fn()} />)
    expect(await screen.findByText('연결됨')).toBeInTheDocument()

    const listeners = mocks.rfbCreated.mock.calls[0][1] as Map<string, (event: CustomEvent) => void>
    act(() => listeners.get('disconnect')?.(new CustomEvent('disconnect')))

    expect(await screen.findByText('연결이 끊겨 다시 연결합니다. (1/3)')).toBeInTheDocument()
    await waitFor(() => expect(mocks.create).toHaveBeenCalledTimes(2), {
      timeout: SCREEN_WALL_RECONNECT_DELAY_MS + 2_000,
    })
    expect(mocks.close).toHaveBeenCalledWith('wall-session')
    expect(mocks.rfbCreated).toHaveBeenCalledTimes(2)
  })
})
