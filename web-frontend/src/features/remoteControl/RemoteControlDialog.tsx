import { useCallback, useEffect, useRef, useState } from 'react'
import { ClipboardCopy, ClipboardPaste, Expand, Eye, EyeOff, FileUp, Keyboard, LoaderCircle, Maximize2, Minimize2, RefreshCw, X } from 'lucide-react'
import { useAuth } from '../../auth/AuthProvider'
import { useToast } from '../../components/ui/ToastProvider'
import type { PcStatus } from '../../types/dashboard'
import {
  closeRemoteControlSession,
  closeRemoteControlSessionOnPageExit,
  createRemoteControlSession,
  getRemoteControlSession,
  issueRemoteControlTicket,
  remoteControlWebSocketUrl,
} from './api'
import { queueRemoteDesktopFiles, waitForRemoteFileTransfers } from './fileTransfer'
import { calculateRemoteViewport, type RemoteDisplayLayout, type RemoteScreenMode } from './viewport'
import './remote-control.css'

interface Props {
  pc: PcStatus
  onClose: () => void
  mode?: 'control' | 'view'
}

interface RfbClient {
  _display?: { scale: number }
  viewOnly: boolean
  scaleViewport: boolean
  resizeSession: boolean
  focusOnClick: boolean
  qualityLevel: number
  compressionLevel: number
  disconnect: () => void
  focus: () => void
  clipboardPasteFrom: (text: string) => void
  sendKey: (keysym: number, code: string, down?: boolean) => void
  sendCtrlAltDel: () => void
  sendCredentials: (credentials: { username: string; password: string; target: string }) => void
  addEventListener: (type: string, listener: (event: CustomEvent) => void) => void
}

type ViewerState = 'starting' | 'waiting' | 'connecting' | 'connected' | 'failed'

const CONTROL_LEFT_KEYSYM = 0xffe3
const V_KEYSYM = 0x0076

function setStyle(style: CSSStyleDeclaration, property: string, value: string) {
  if (style.getPropertyValue(property) !== value) style.setProperty(property, value)
}

function wait(milliseconds: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const timeout = window.setTimeout(resolve, milliseconds)
    signal.addEventListener('abort', () => {
      window.clearTimeout(timeout)
      reject(new DOMException('Aborted', 'AbortError'))
    }, { once: true })
  })
}

function failureMessage(error: unknown) {
  if (error instanceof Error) return error.message
  return '원격 제어 연결을 시작하지 못했습니다.'
}

export function RemoteControlDialog({ pc, onClose, mode = 'control' }: Props) {
  const { token } = useAuth()
  const { showToast } = useToast()
  const viewerOnly = mode === 'view'
  const remoteLabel = viewerOnly ? '원격 화면' : '원격 제어'
  const rfbTargetRef = useRef<HTMLDivElement>(null)
  const dialogRef = useRef<HTMLElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const rfbRef = useRef<RfbClient | null>(null)
  const sessionIdRef = useRef<string | null>(null)
  const cleanupPromiseRef = useRef<Promise<void> | null>(null)
  const closingRef = useRef(false)
  const viewOnlyRef = useRef(viewerOnly)
  const scaleViewportRef = useRef(true)
  const screenModeRef = useRef<RemoteScreenMode>('all')
  const pasteEventHandledRef = useRef(false)
  const forwardedKeyboardEventsRef = useRef(new WeakSet<KeyboardEvent>())
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<ViewerState>('starting')
  const [statusText, setStatusText] = useState(`에이전트에 ${remoteLabel} 시작을 요청하는 중입니다.`)
  const [viewOnly, setViewOnly] = useState(viewerOnly)
  const [scaleViewport, setScaleViewport] = useState(true)
  const [screenMode, setScreenMode] = useState<RemoteScreenMode>('all')
  const [displayLayout, setDisplayLayout] = useState<RemoteDisplayLayout>('single')
  const [remoteClipboardText, setRemoteClipboardText] = useState('')
  const [fileTransferBusy, setFileTransferBusy] = useState(false)
  const [fileTransferText, setFileTransferText] = useState('')
  const [fullscreen, setFullscreen] = useState(false)

  const applyViewportLayout = useCallback(() => {
    const target = rfbTargetRef.current
    const canvas = target?.querySelector('canvas')
    const display = canvas?.parentElement
    if (!target || !canvas || !display || canvas.width <= 0 || canvas.height <= 0
      || target.clientWidth <= 0 || target.clientHeight <= 0) return

    const layout = calculateRemoteViewport({
      framebufferWidth: canvas.width,
      framebufferHeight: canvas.height,
      viewportWidth: target.clientWidth,
      viewportHeight: target.clientHeight,
      mode: screenModeRef.current,
      fit: scaleViewportRef.current,
    })
    setDisplayLayout((current) => current === layout.displayLayout ? current : layout.displayLayout)

    setStyle(display.style, 'display', 'block')
    setStyle(display.style, 'position', 'relative')
    setStyle(display.style, 'flex', '0 0 auto')
    setStyle(display.style, 'width', `${layout.displayWidth}px`)
    setStyle(display.style, 'height', `${layout.displayHeight}px`)
    setStyle(display.style, 'overflow', 'hidden')
    setStyle(display.style, 'margin', scaleViewportRef.current ? 'auto' : '0')
    const scale = layout.canvasWidth / canvas.width
    if (rfbRef.current?._display) {
      // noVNC uses this scale to convert browser pointer coordinates back to framebuffer coordinates.
      rfbRef.current._display.scale = scale
    } else {
      setStyle(canvas.style, 'width', `${layout.canvasWidth}px`)
      setStyle(canvas.style, 'height', `${layout.canvasHeight}px`)
    }
    setStyle(canvas.style, 'position', 'absolute')
    setStyle(canvas.style, 'max-width', 'none')
    setStyle(canvas.style, 'max-height', 'none')
    setStyle(canvas.style, 'margin', '0')
    setStyle(canvas.style, 'left', `${layout.canvasLeft}px`)
    setStyle(canvas.style, 'top', `${layout.canvasTop}px`)
    setStyle(canvas.style, 'transform', 'none')
  }, [])

  const sendRemotePaste = useCallback((text: string) => {
    const client = rfbRef.current
    if (!client || client.viewOnly || !text) return
    client.clipboardPasteFrom(text)
    window.setTimeout(() => {
      if (rfbRef.current !== client || client.viewOnly) return
      client.sendKey(CONTROL_LEFT_KEYSYM, 'ControlLeft', true)
      client.sendKey(V_KEYSYM, 'KeyV', true)
      client.sendKey(V_KEYSYM, 'KeyV', false)
      client.sendKey(CONTROL_LEFT_KEYSYM, 'ControlLeft', false)
    }, 80)
  }, [])

  const transferRemoteFiles = useCallback(async (files: File[]) => {
    if (!files.length) return
    if (!token) {
      showToast('로그인이 만료되었습니다.', 'error')
      return
    }
    if (state !== 'connected' || viewOnly) return
    if (fileTransferBusy) {
      showToast('다른 파일을 전송하고 있습니다.', 'error')
      return
    }

    setFileTransferBusy(true)
    setFileTransferText('파일 업로드 준비 중')
    try {
      const taskIds = await queueRemoteDesktopFiles({
        files,
        pc,
        token,
        onProgress: ({ fileIndex, totalFiles, upload }) => {
          const current = upload.phase === 'assembling' || upload.phase === 'complete'
            ? 1
            : upload.uploadedBytes / Math.max(1, upload.totalBytes)
          const percent = Math.round(((fileIndex + current) / totalFiles) * 100)
          setFileTransferText(`파일 업로드 ${percent}%`)
        },
      })
      setFileTransferText('원격 바탕화면에 저장 중')
      const statuses = await waitForRemoteFileTransfers(taskIds)
      const failed = statuses.filter((task) => task.status.toUpperCase() !== 'COMPLETED')
      if (failed.length) {
        showToast(`${failed.length}개 파일을 원격 PC에 저장하지 못했습니다.`, 'error')
      } else {
        showToast(`${files.length}개 파일을 ${pc.pcName || pc.ipAddress} 바탕화면에 전송했습니다.`)
      }
    } catch (error) {
      showToast(error instanceof Error ? error.message : '파일을 원격 PC에 전송하지 못했습니다.', 'error')
    } finally {
      setFileTransferBusy(false)
      setFileTransferText('')
    }
  }, [fileTransferBusy, pc, showToast, state, token, viewOnly])

  const copyRemoteClipboard = useCallback(async () => {
    if (!remoteClipboardText || !navigator.clipboard?.writeText) return
    try {
      await navigator.clipboard.writeText(remoteClipboardText)
    } catch {
      showToast('브라우저에서 클립보드 쓰기 권한을 허용해 주세요.', 'error')
    }
  }, [remoteClipboardText, showToast])

  const pasteBrowserClipboard = useCallback(async () => {
    if (!navigator.clipboard?.readText) {
      showToast('이 브라우저에서는 클립보드 읽기를 지원하지 않습니다.', 'error')
      return
    }
    try {
      const text = await navigator.clipboard.readText()
      if (!text) {
        showToast('클립보드에 붙여넣을 텍스트가 없습니다.', 'error')
        return
      }
      sendRemotePaste(text)
    } catch {
      showToast('브라우저에서 클립보드 읽기 권한을 허용해 주세요.', 'error')
    }
  }, [sendRemotePaste, showToast])

  const handleRemoteKeyDownCapture = (event: React.KeyboardEvent<HTMLElement>) => {
    if (state !== 'connected' || viewOnly) return
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'v') {
      // Let the browser emit a paste event first so copied files remain accessible.
      pasteEventHandledRef.current = false
      event.stopPropagation()
      window.setTimeout(() => {
        if (!pasteEventHandledRef.current) void pasteBrowserClipboard()
      }, 0)
    }
  }

  const handleRemotePasteCapture = (event: React.ClipboardEvent<HTMLDivElement>) => {
    if (state !== 'connected' || viewOnly) return
    pasteEventHandledRef.current = true
    event.preventDefault()
    event.stopPropagation()
    if (event.clipboardData.files.length > 0) {
      void transferRemoteFiles(Array.from(event.clipboardData.files))
      return
    }
    sendRemotePaste(event.clipboardData.getData('text/plain'))
  }

  const releaseSession = useCallback(() => {
    if (closingRef.current) return
    closingRef.current = true
    try {
      rfbRef.current?.disconnect()
    } catch {
      // The relay may already be disconnected.
    }
    rfbRef.current = null
    const sessionId = sessionIdRef.current
    sessionIdRef.current = null
    if (sessionId) {
      void closeRemoteControlSession(sessionId).catch(() => undefined)
    }
  }, [])

  const closeViewer = useCallback(() => {
    releaseSession()
    onClose()
  }, [onClose, releaseSession])

  useEffect(() => {
    const controller = new AbortController()
    closingRef.current = false
    setState('starting')
    setStatusText(`에이전트에 ${remoteLabel} 시작을 요청하는 중입니다.`)

    const connect = async () => {
      let oneTimePassword = ''
      try {
        let session = await createRemoteControlSession(pc.macAddress)
        if (controller.signal.aborted || closingRef.current) {
          void closeRemoteControlSession(session.sessionId).catch(() => undefined)
          return
        }
        sessionIdRef.current = session.sessionId
        setState('waiting')
        setStatusText('에이전트가 원격 화면을 준비하는 중입니다.')

        while (session.status === 'PENDING') {
          await wait(800, controller.signal)
          session = await getRemoteControlSession(session.sessionId)
        }
        if (session.status === 'FAILED' || session.status === 'CLOSED') {
          throw new Error(session.failureReason || '에이전트가 원격 제어를 준비하지 못했습니다.')
        }
        if (session.status !== 'READY') {
          throw new Error(`원격 제어 세션 상태가 올바르지 않습니다: ${session.status}`)
        }

        const ticket = await issueRemoteControlTicket(session.sessionId)
        if (controller.signal.aborted || !rfbTargetRef.current) return
        oneTimePassword = ticket.oneTimePassword
        setState('connecting')
        setStatusText('암호화된 중계 연결을 여는 중입니다.')
        const { default: RFB } = await import('@novnc/novnc')
        if (controller.signal.aborted || !rfbTargetRef.current) return
        rfbTargetRef.current.replaceChildren()
        const credentials = { username: '', password: oneTimePassword, target: '' }
        const client = new RFB(
          rfbTargetRef.current,
          remoteControlWebSocketUrl(ticket.webSocketPath),
          { credentials, shared: true },
        ) as unknown as RfbClient
        rfbRef.current = client
        client.scaleViewport = false
        client.resizeSession = false
        client.focusOnClick = !viewerOnly
        client.viewOnly = viewOnlyRef.current
        client.qualityLevel = 7
        client.compressionLevel = 3
        client.addEventListener('credentialsrequired', () => {
          if (oneTimePassword) client.sendCredentials(credentials)
        })
        client.addEventListener('connect', () => {
          oneTimePassword = ''
          credentials.password = ''
          setState('connected')
          setStatusText('연결됨')
          window.requestAnimationFrame(applyViewportLayout)
          client.focus()
        })
        client.addEventListener('clipboard', (event) => {
          const text = (event as CustomEvent<{ text?: string }>).detail?.text
          if (typeof text !== 'string') return
          setRemoteClipboardText(text)
          if (navigator.clipboard?.writeText) {
            void navigator.clipboard.writeText(text).catch(() => undefined)
          }
        })
        client.addEventListener('securityfailure', (event) => {
          const reason = (event as CustomEvent<{ reason?: string }>).detail?.reason
          setState('failed')
          setStatusText(reason || '원격 화면 인증에 실패했습니다.')
        })
        client.addEventListener('disconnect', (event) => {
          const clean = (event as CustomEvent<{ clean?: boolean }>).detail?.clean === true
          if (!closingRef.current) {
            const sessionId = sessionIdRef.current
            sessionIdRef.current = null
            if (sessionId) {
              const cleanup = closeRemoteControlSession(sessionId).catch(() => undefined)
              cleanupPromiseRef.current = cleanup
              void cleanup.finally(() => {
                if (cleanupPromiseRef.current === cleanup) cleanupPromiseRef.current = null
              })
            }
            setState('failed')
            setStatusText(clean
              ? `${remoteLabel} 연결이 종료되었습니다.`
              : '대상 PC의 원격 화면 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.')
          }
        })
      } catch (error) {
        if (controller.signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) return
        oneTimePassword = ''
        setState('failed')
        setStatusText(failureMessage(error))
        const sessionId = sessionIdRef.current
        sessionIdRef.current = null
        if (sessionId) void closeRemoteControlSession(sessionId).catch(() => undefined)
      }
    }

    // React StrictMode runs an immediate setup/cleanup cycle in development.
    // Deferring the request prevents that probe from creating a throwaway VNC session.
    const startTimer = window.setTimeout(() => void connect(), 0)
    return () => {
      window.clearTimeout(startTimer)
      controller.abort()
      try {
        rfbRef.current?.disconnect()
      } catch {
        // The browser may already have torn the WebSocket down.
      }
      rfbRef.current = null
      const sessionId = sessionIdRef.current
      sessionIdRef.current = null
      if (sessionId && !closingRef.current) void closeRemoteControlSession(sessionId).catch(() => undefined)
    }
  }, [applyViewportLayout, attempt, pc.macAddress, remoteLabel, viewerOnly])

  const toggleViewOnly = () => {
    setViewOnly((current) => {
      const next = !current
      viewOnlyRef.current = next
      if (rfbRef.current) rfbRef.current.viewOnly = next
      return next
    })
  }

  useEffect(() => {
    const target = rfbTargetRef.current
    if (!target) return
    const resizeObserver = typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(applyViewportLayout)
    const mutationObserver = typeof MutationObserver === 'undefined'
      ? null
      : new MutationObserver(applyViewportLayout)
    resizeObserver?.observe(target)
    mutationObserver?.observe(target, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['width', 'height', 'style'],
    })
    applyViewportLayout()
    return () => {
      resizeObserver?.disconnect()
      mutationObserver?.disconnect()
    }
  }, [applyViewportLayout])

  useEffect(() => {
    scaleViewportRef.current = scaleViewport
    screenModeRef.current = screenMode
    applyViewportLayout()
  }, [applyViewportLayout, scaleViewport, screenMode])

  useEffect(() => {
    screenModeRef.current = 'all'
    setScreenMode('all')
    setDisplayLayout('single')
  }, [pc.macAddress])

  useEffect(() => {
    if (state !== 'connected' || viewOnly) return
    const stage = rfbTargetRef.current
    const canvas = stage?.querySelector('canvas')
    if (!stage || !canvas) return

    const forwardKeyboardEvent = (event: KeyboardEvent) => {
      if (forwardedKeyboardEventsRef.current.has(event) || event.isComposing) return
      const eventTarget = event.target
      const activeInRemote = document.activeElement === canvas
        || (eventTarget instanceof Node && stage.contains(eventTarget))
      const modifierEvent = event.ctrlKey || event.altKey || event.metaKey
        || event.key === 'Control' || event.key === 'Alt' || event.key === 'Meta'
      if (!activeInRemote && !modifierEvent) return

      event.preventDefault()
      event.stopImmediatePropagation()
      const forwarded = new KeyboardEvent(event.type, {
        key: event.key,
        code: event.code,
        location: event.location,
        repeat: event.repeat,
        ctrlKey: event.ctrlKey,
        shiftKey: event.shiftKey,
        altKey: event.altKey,
        metaKey: event.metaKey,
        bubbles: true,
        cancelable: true,
      })
      forwardedKeyboardEventsRef.current.add(forwarded)
      rfbRef.current?.focus()
      canvas.dispatchEvent(forwarded)
    }

    document.addEventListener('keydown', forwardKeyboardEvent, true)
    document.addEventListener('keyup', forwardKeyboardEvent, true)
    return () => {
      document.removeEventListener('keydown', forwardKeyboardEvent, true)
      document.removeEventListener('keyup', forwardKeyboardEvent, true)
    }
  }, [state, viewOnly])

  useEffect(() => {
    const blockLocalContextMenu = (event: MouseEvent) => {
      const stage = rfbTargetRef.current
      if (!stage || !(event.target instanceof Node) || !stage.contains(event.target)) return
      event.preventDefault()
      event.stopImmediatePropagation()
    }
    document.addEventListener('contextmenu', blockLocalContextMenu, true)
    return () => document.removeEventListener('contextmenu', blockLocalContextMenu, true)
  }, [])

  useEffect(() => {
    const onFullscreenChange = () => setFullscreen(document.fullscreenElement === dialogRef.current)
    document.addEventListener('fullscreenchange', onFullscreenChange)
    return () => document.removeEventListener('fullscreenchange', onFullscreenChange)
  }, [])

  useEffect(() => {
    const onPageHide = () => {
      const sessionId = sessionIdRef.current
      if (!sessionId || !token || closingRef.current) return
      closingRef.current = true
      closeRemoteControlSessionOnPageExit(sessionId, token)
    }
    window.addEventListener('pagehide', onPageHide)
    return () => window.removeEventListener('pagehide', onPageHide)
  }, [token])

  const retry = async () => {
    await cleanupPromiseRef.current
    closingRef.current = false
    setAttempt((current) => current + 1)
  }

  const toggleFullscreen = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen()
      else await dialogRef.current?.requestFullscreen()
    } catch {
      showToast('전체화면으로 전환하지 못했습니다.', 'error')
    }
  }

  return (
    <div className="remote-control-backdrop" role="presentation">
      <section
        ref={dialogRef}
        className="remote-control-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="remote-control-title"
        onKeyDownCapture={handleRemoteKeyDownCapture}
      >
        <header className="remote-control-header">
          <div>
            <h2 id="remote-control-title">{pc.pcName || pc.ipAddress} {remoteLabel}</h2>
            <p>{pc.groupName || '미분류'} · {pc.ipAddress || '-'} · {statusText}{fileTransferText ? ` · ${fileTransferText}` : ''}</p>
          </div>
          <div className="remote-control-tools">
            {displayLayout !== 'single' && (
              <div className="remote-screen-segments" role="group" aria-label="화면 선택">
                <button className={screenMode === 'all' ? 'active' : ''} onClick={() => setScreenMode('all')}>전체</button>
                <button className={screenMode === 'first' ? 'active' : ''} onClick={() => setScreenMode('first')}>화면 1</button>
                <button className={screenMode === 'second' ? 'active' : ''} onClick={() => setScreenMode('second')}>화면 2</button>
              </div>
            )}
            {!viewerOnly && (
              <button className={`icon-button bordered ${viewOnly ? 'active' : ''}`} onClick={toggleViewOnly} aria-label={viewOnly ? '제어 허용' : '보기 전용'} title={viewOnly ? '제어 허용' : '보기 전용'}>
                {viewOnly ? <EyeOff size={17} /> : <Eye size={17} />}
              </button>
            )}
            <button className="icon-button bordered" onClick={() => void copyRemoteClipboard()} disabled={state !== 'connected' || !remoteClipboardText} aria-label="원격 클립보드 복사" title="원격 클립보드를 내 PC로 복사"><ClipboardCopy size={17} /></button>
            {!viewerOnly && (
              <>
                <button className="icon-button bordered" onClick={() => void pasteBrowserClipboard()} disabled={state !== 'connected' || viewOnly} aria-label="원격 클립보드 붙여넣기" title="내 클립보드를 원격 PC에 붙여넣기"><ClipboardPaste size={17} /></button>
                <button className="icon-button bordered" onClick={() => fileInputRef.current?.click()} disabled={state !== 'connected' || viewOnly || fileTransferBusy} aria-label="원격 PC로 파일 보내기" title="원격 PC 바탕화면으로 파일 보내기">
                  {fileTransferBusy ? <LoaderCircle size={17} className="spin-icon" /> : <FileUp size={17} />}
                </button>
                <input
                  ref={fileInputRef}
                  className="remote-file-input"
                  type="file"
                  multiple
                  tabIndex={-1}
                  onChange={(event) => {
                    void transferRemoteFiles(Array.from(event.target.files || []))
                    event.target.value = ''
                  }}
                />
              </>
            )}
            <button className={`icon-button bordered ${scaleViewport ? 'active' : ''}`} onClick={() => setScaleViewport((current) => !current)} aria-label="화면 맞춤" title="화면 맞춤"><Expand size={17} /></button>
            {!viewerOnly && <button className="icon-button bordered" onClick={() => rfbRef.current?.sendCtrlAltDel()} disabled={state !== 'connected' || viewOnly} aria-label="Ctrl Alt Delete 전송" title="Ctrl+Alt+Delete"><Keyboard size={17} /></button>}
            <button className="icon-button bordered" onClick={() => void toggleFullscreen()} aria-label={fullscreen ? '전체화면 종료' : '전체화면'} title={fullscreen ? '전체화면 종료' : '전체화면'}>
              {fullscreen ? <Minimize2 size={17} /> : <Maximize2 size={17} />}
            </button>
            <button className="icon-button bordered remote-close" onClick={closeViewer} aria-label={`${remoteLabel} 종료`} title={`${remoteLabel} 종료`}><X size={18} /></button>
          </div>
        </header>
        <div className="remote-control-viewport">
          <div
            className={`remote-control-stage ${scaleViewport ? 'fit' : 'actual'}`}
            ref={rfbTargetRef}
            onPasteCapture={handleRemotePasteCapture}
            onDragOver={(event) => {
              if (event.dataTransfer.types.includes('Files')) event.preventDefault()
            }}
            onDrop={(event) => {
              if (!event.dataTransfer.files.length) return
              event.preventDefault()
              void transferRemoteFiles(Array.from(event.dataTransfer.files))
            }}
          />
          {state !== 'connected' && (
            <div className={`remote-control-status ${state === 'failed' ? 'failed' : ''}`} role={state === 'failed' ? 'alert' : 'status'}>
              {state === 'failed' ? <X size={30} /> : <RefreshCw size={30} className="spin-icon" />}
              <strong>{statusText}</strong>
              {state === 'failed' && <button className="button secondary" onClick={retry}><RefreshCw size={15} />다시 연결</button>}
            </div>
          )}
        </div>
      </section>
    </div>
  )
}
