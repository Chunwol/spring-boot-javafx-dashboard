import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RemoteControlDialog } from './RemoteControlDialog'

const mocks = vi.hoisted(() => ({
  create: vi.fn(),
  get: vi.fn(),
  ticket: vi.fn(),
  close: vi.fn(),
  exit: vi.fn(),
  disconnect: vi.fn(),
  clipboardPaste: vi.fn(),
  sendKey: vi.fn(),
  keyboardEvent: vi.fn(),
  emitClipboard: vi.fn(),
  queueFiles: vi.fn(),
  waitFiles: vi.fn(),
  emitDisconnect: vi.fn(),
  rfbCreated: vi.fn(),
  showToast: vi.fn(),
}))

vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ token: 'test-jwt' }),
}))

vi.mock('../../components/ui/ToastProvider', () => ({
  useToast: () => ({ showToast: mocks.showToast }),
}))

vi.mock('./api', () => ({
  createRemoteControlSession: mocks.create,
  getRemoteControlSession: mocks.get,
  issueRemoteControlTicket: mocks.ticket,
  closeRemoteControlSession: mocks.close,
  closeRemoteControlSessionOnPageExit: mocks.exit,
  remoteControlWebSocketUrl: () => 'ws://localhost/vnc-relay/session?ticket=secret',
}))

vi.mock('./fileTransfer', () => ({
  queueRemoteDesktopFiles: mocks.queueFiles,
  waitForRemoteFileTransfers: mocks.waitFiles,
}))

vi.mock('@novnc/novnc', () => ({
  default: function MockRfb(target: HTMLElement) {
    Object.defineProperties(target, {
      clientWidth: { configurable: true, value: 1600 },
      clientHeight: { configurable: true, value: 900 },
    })
    const display = document.createElement('div')
    const canvas = document.createElement('canvas')
    canvas.width = 3840
    canvas.height = 1080
    canvas.style.width = '3840px'
    canvas.style.height = '1080px'
    canvas.tabIndex = -1
    display.appendChild(canvas)
    target.appendChild(display)
    const handleKeyboard = (event: KeyboardEvent) => {
      mocks.keyboardEvent(event.type, event.key, event.code)
      const down = event.type === 'keydown'
      if (event.key === 'Control') mocks.sendKey(0xffe3, event.code, down)
      else if (event.key.length === 1) mocks.sendKey(event.key.codePointAt(0), event.code, down)
      event.preventDefault()
      event.stopPropagation()
    }
    canvas.addEventListener('keydown', handleKeyboard)
    canvas.addEventListener('keyup', handleKeyboard)
    let displayScale = 1
    const client = {
      _display: {
        get scale() { return displayScale },
        set scale(value: number) {
          displayScale = value
          canvas.style.width = `${canvas.width * value}px`
          canvas.style.height = `${canvas.height * value}px`
        },
      },
      viewOnly: false,
      scaleViewport: true,
      resizeSession: false,
      focusOnClick: true,
      qualityLevel: 0,
      compressionLevel: 0,
      disconnect: mocks.disconnect,
      focus: vi.fn(() => canvas.focus()),
      clipboardPasteFrom: mocks.clipboardPaste,
      sendKey: mocks.sendKey,
      sendCtrlAltDel: vi.fn(),
      sendCredentials: vi.fn(),
      addEventListener(type: string, listener: (event: CustomEvent) => void) {
        if (type === 'connect') queueMicrotask(() => listener(new CustomEvent('connect')))
        if (type === 'disconnect') {
          mocks.emitDisconnect.mockImplementation((clean = false) => {
            listener(new CustomEvent('disconnect', { detail: { clean } }))
          })
        }
        if (type === 'clipboard') {
          mocks.emitClipboard.mockImplementation((text: string) => {
            listener(new CustomEvent('clipboard', { detail: { text } }))
          })
        }
      },
    }
    mocks.rfbCreated(client)
    return client
  },
}))

const pc = {
  macAddress: 'AA:BB:CC:DD:EE:FF',
  pcName: '218-01',
  ipAddress: '192.168.10.11',
  groupId: 218,
  groupName: '218호',
  online: true,
}

const readySession = {
  sessionId: 'session-id', taskId: 7, macAddress: pc.macAddress,
  status: 'READY', expiresAt: '2026-08-07T19:00:00Z',
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

describe('RemoteControlDialog', () => {
  afterEach(cleanup)

  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset())
    mocks.create.mockResolvedValue(readySession)
    mocks.ticket.mockResolvedValue({
      sessionId: 'session-id', taskId: 7,
      webSocketPath: '/vnc-relay/session-id?ticket=secret',
      oneTimePassword: 'Abcd1234', ticketExpiresAt: '2026-08-07T18:30:00Z',
    })
    mocks.close.mockResolvedValue(undefined)
    mocks.queueFiles.mockResolvedValue([91])
    mocks.waitFiles.mockResolvedValue([{ taskId: 91, status: 'COMPLETED' }])
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        readText: vi.fn().mockResolvedValue('local clipboard'),
        writeText: vi.fn().mockResolvedValue(undefined),
      },
    })
  })

  it('connects through a one-time ticket and closes the server session on unmount', async () => {
    const view = render(<RemoteControlDialog pc={pc} onClose={vi.fn()} />)

    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()
    expect(mocks.create).toHaveBeenCalledWith(pc.macAddress)
    expect(mocks.ticket).toHaveBeenCalledWith('session-id')

    view.unmount()
    await waitFor(() => expect(mocks.close).toHaveBeenCalledWith('session-id'))
    expect(mocks.disconnect).toHaveBeenCalled()
  })

  it('applies a view-only change made before the RFB client is created', async () => {
    const creation = deferred<typeof readySession>()
    mocks.create.mockReturnValue(creation.promise)
    render(<RemoteControlDialog pc={pc} onClose={vi.fn()} />)

    await waitFor(() => expect(mocks.create).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: '보기 전용' }))
    expect(screen.getByRole('button', { name: '제어 허용' })).toBeInTheDocument()

    await act(async () => creation.resolve(readySession))
    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()
    expect(mocks.rfbCreated).toHaveBeenCalledTimes(1)
    expect(mocks.rfbCreated.mock.calls[0][0].viewOnly).toBe(true)
  })

  it('opens PC management viewer mode as view-only without control actions', async () => {
    render(<RemoteControlDialog pc={pc} mode="view" onClose={vi.fn()} />)

    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '218-01 원격 화면' })).toBeInTheDocument()
    expect(mocks.rfbCreated.mock.calls[0][0].viewOnly).toBe(true)
    expect(mocks.rfbCreated.mock.calls[0][0].focusOnClick).toBe(false)
    expect(screen.queryByRole('button', { name: '제어 허용' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '원격 클립보드 붙여넣기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '원격 PC로 파일 보내기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ctrl Alt Delete 전송' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '원격 화면 종료' })).toBeInTheDocument()
  })

  it('closes a session whose create response arrives after the dialog is closed', async () => {
    const creation = deferred<typeof readySession>()
    const onClose = vi.fn()
    mocks.create.mockReturnValue(creation.promise)
    const view = render(<RemoteControlDialog pc={pc} onClose={onClose} />)

    await waitFor(() => expect(mocks.create).toHaveBeenCalledTimes(1))
    fireEvent.click(screen.getByRole('button', { name: '원격 제어 종료' }))
    expect(onClose).toHaveBeenCalledTimes(1)
    view.unmount()

    await act(async () => creation.resolve(readySession))
    await waitFor(() => expect(mocks.close).toHaveBeenCalledWith('session-id'))
    expect(mocks.ticket).not.toHaveBeenCalled()
  })

  it('closes the UI immediately while server session cleanup is still pending', async () => {
    const serverClose = deferred<void>()
    const onClose = vi.fn()
    mocks.close.mockReturnValue(serverClose.promise)
    render(<RemoteControlDialog pc={pc} onClose={onClose} />)

    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '원격 제어 종료' }))

    expect(mocks.disconnect).toHaveBeenCalledTimes(1)
    expect(mocks.close).toHaveBeenCalledWith('session-id')
    expect(onClose).toHaveBeenCalledTimes(1)
    await act(async () => serverClose.resolve())
  })

  it('does not create a duplicate session during the StrictMode effect probe', async () => {
    render(
      <StrictMode>
        <RemoteControlDialog pc={pc} onClose={vi.fn()} />
      </StrictMode>,
    )

    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()
    expect(mocks.create).toHaveBeenCalledTimes(1)
  })

  it('uses keepalive cleanup when the page is hidden', async () => {
    render(<RemoteControlDialog pc={pc} onClose={vi.fn()} />)
    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()

    window.dispatchEvent(new Event('pagehide'))

    expect(mocks.exit).toHaveBeenCalledWith('session-id', 'test-jwt')
  })

  it('closes the server session immediately when the relay disconnects', async () => {
    render(<RemoteControlDialog pc={pc} onClose={vi.fn()} />)
    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()

    act(() => mocks.emitDisconnect(false))

    expect(await screen.findAllByText(/대상 PC의 원격 화면 서버에 연결할 수 없습니다/)).not.toHaveLength(0)
    await waitFor(() => expect(mocks.close).toHaveBeenCalledWith('session-id'))
  })

  it('does not reconnect when the viewport fit mode changes', async () => {
    render(<RemoteControlDialog pc={pc} onClose={vi.fn()} />)
    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '화면 맞춤' }))

    await waitFor(() => expect(mocks.create).toHaveBeenCalledTimes(1))
    expect(mocks.rfbCreated).toHaveBeenCalledTimes(1)
  })

  it('fits the framebuffer through the noVNC display scale so pointer coordinates stay aligned', async () => {
    const view = render(<RemoteControlDialog pc={pc} onClose={vi.fn()} />)
    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()

    const canvas = view.container.querySelector('canvas')
    expect(canvas).not.toBeNull()
    await waitFor(() => {
      expect(canvas?.parentElement?.style.width).toBe('1600px')
      expect(canvas?.parentElement?.style.height).toBe('450px')
      expect(canvas?.style.width).toBe('1600px')
      expect(canvas?.style.height).toBe('450px')
      expect(canvas?.style.left).toBe('0px')
      expect(canvas?.style.transform).toBe('none')
      expect(mocks.rfbCreated.mock.calls[0][0]._display.scale).toBeCloseTo(1600 / 3840)
    })

    fireEvent.click(screen.getByRole('button', { name: '화면 2' }))
    await waitFor(() => {
      expect(canvas?.parentElement?.style.width).toBe('1600px')
      expect(canvas?.parentElement?.style.height).toBe('900px')
      expect(canvas?.style.width).toBe('3200px')
      expect(canvas?.style.height).toBe('900px')
      expect(canvas?.style.left).toBe('-1600px')
      expect(mocks.rfbCreated.mock.calls[0][0]._display.scale).toBeCloseTo(1600 / 1920)
    })
  })

  it('synchronizes text clipboard data in both directions', async () => {
    const view = render(<RemoteControlDialog pc={pc} onClose={vi.fn()} />)
    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()
    const canvas = view.container.querySelector('canvas') as HTMLCanvasElement

    fireEvent.keyDown(canvas, { key: 'v', code: 'KeyV', ctrlKey: true })

    await waitFor(() => expect(navigator.clipboard.readText).toHaveBeenCalled())
    expect(mocks.clipboardPaste).toHaveBeenCalledWith('local clipboard')
    await waitFor(() => expect(mocks.sendKey).toHaveBeenCalledTimes(4))
    expect(mocks.sendKey.mock.calls).toEqual([
      [0xffe3, 'ControlLeft', true],
      [0x0076, 'KeyV', true],
      [0x0076, 'KeyV', false],
      [0xffe3, 'ControlLeft', false],
    ])

    act(() => mocks.emitClipboard('remote text'))
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledWith('remote text'))
    expect(screen.getByRole('button', { name: '원격 클립보드 복사' })).toBeEnabled()
  })

  it('forwards document-level Ctrl shortcuts to the active remote canvas once', async () => {
    const view = render(<RemoteControlDialog pc={pc} onClose={vi.fn()} />)
    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()
    const canvas = view.container.querySelector('canvas') as HTMLCanvasElement
    canvas.focus()
    const events = [
      new KeyboardEvent('keydown', { key: 'Control', code: 'ControlLeft', ctrlKey: true, bubbles: true, cancelable: true }),
      new KeyboardEvent('keydown', { key: 'a', code: 'KeyA', ctrlKey: true, bubbles: true, cancelable: true }),
      new KeyboardEvent('keyup', { key: 'a', code: 'KeyA', ctrlKey: true, bubbles: true, cancelable: true }),
      new KeyboardEvent('keyup', { key: 'Control', code: 'ControlLeft', bubbles: true, cancelable: true }),
    ]

    events.forEach((event) => document.dispatchEvent(event))

    expect(events.every((event) => event.defaultPrevented)).toBe(true)
    expect(mocks.keyboardEvent.mock.calls).toEqual([
      ['keydown', 'Control', 'ControlLeft'],
      ['keydown', 'a', 'KeyA'],
      ['keyup', 'a', 'KeyA'],
      ['keyup', 'Control', 'ControlLeft'],
    ])
    expect(mocks.sendKey.mock.calls).toEqual([
      [0xffe3, 'ControlLeft', true],
      [0x0061, 'KeyA', true],
      [0x0061, 'KeyA', false],
      [0xffe3, 'ControlLeft', false],
    ])
  })

  it('blocks the local context menu without consuming remote mouse button events', async () => {
    const view = render(<RemoteControlDialog pc={pc} onClose={vi.fn()} />)
    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()
    const canvas = view.container.querySelector('canvas') as HTMLCanvasElement
    const canvasContextMenu = vi.fn()
    canvas.addEventListener('contextmenu', canvasContextMenu)
    const mouseDown = new MouseEvent('mousedown', { button: 2, buttons: 2, bubbles: true, cancelable: true })
    const contextMenu = new MouseEvent('contextmenu', { button: 2, bubbles: true, cancelable: true })

    canvas.dispatchEvent(mouseDown)
    canvas.dispatchEvent(contextMenu)

    expect(mouseDown.defaultPrevented).toBe(false)
    expect(contextMenu.defaultPrevented).toBe(true)
    expect(canvasContextMenu).not.toHaveBeenCalled()
  })

  it('queues files pasted into the remote canvas for desktop transfer', async () => {
    const view = render(<RemoteControlDialog pc={pc} onClose={vi.fn()} />)
    expect(await screen.findByText(/연결됨$/)).toBeInTheDocument()
    const canvas = view.container.querySelector('canvas') as HTMLCanvasElement

    fireEvent.paste(canvas, {
      clipboardData: { files: [new File(['x'], 'sample.txt')], getData: () => '' },
    })

    await waitFor(() => expect(mocks.queueFiles).toHaveBeenCalledWith(expect.objectContaining({
      files: [expect.objectContaining({ name: 'sample.txt' })],
      pc,
      token: 'test-jwt',
    })))
    await waitFor(() => expect(mocks.waitFiles).toHaveBeenCalledWith([91]))
    expect(mocks.clipboardPaste).not.toHaveBeenCalled()
  })
})
