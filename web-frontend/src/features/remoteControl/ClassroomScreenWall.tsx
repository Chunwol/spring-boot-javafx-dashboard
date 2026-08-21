import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Expand, LoaderCircle, Minimize2, RefreshCw, X } from 'lucide-react'
import { useAuth } from '../../auth/AuthProvider'
import { useDialogFocus } from '../../components/ui/useDialogFocus'
import { isBrokenPc, isOnlinePc, seatLabel } from '../classrooms/model'
import type { ClassroomSummary, PcStatus } from '../../types/dashboard'
import {
  closeRemoteControlSession,
  closeRemoteControlSessionsOnPageExit,
  createRemoteControlSession,
  getRemoteControlSession,
  issueRemoteControlTicket,
  remoteControlWebSocketUrl,
} from './api'
import {
  canStartScreenWallPc,
  nextScreenWallPollDelay,
  nextScreenWallStartCount,
  screenWallPcs,
  SCREEN_WALL_BATCH_INTERVAL_MS,
  SCREEN_WALL_BATCH_SIZE,
  SCREEN_WALL_STATUS_POLL_INITIAL_MS,
} from './screenWall'
import './screen-wall.css'

interface Props {
  room: ClassroomSummary
  pcs: PcStatus[]
  onClose: () => void
}

interface RfbClient {
  viewOnly: boolean
  scaleViewport: boolean
  resizeSession: boolean
  focusOnClick: boolean
  qualityLevel: number
  compressionLevel: number
  disconnect: () => void
  sendCredentials: (credentials: { username: string; password: string; target: string }) => void
  addEventListener: (type: string, listener: (event: CustomEvent) => void) => void
}

type TileState = 'queued' | 'starting' | 'waiting' | 'connecting' | 'connected' | 'failed' | 'offline' | 'broken'

const tileStateLabel: Record<TileState, string> = {
  queued: '연결 대기',
  starting: '시작 요청 중',
  waiting: '화면 준비 중',
  connecting: '연결 중',
  connected: '연결됨',
  failed: '연결 실패',
  offline: '오프라인',
  broken: '고장',
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

function initialTileState(pc: PcStatus): TileState {
  if (isBrokenPc(pc)) return 'broken'
  if (!isOnlinePc(pc)) return 'offline'
  return 'queued'
}

function ScreenWallTile({ pc, enabled, onStateChange, onSessionChange }: {
  pc: PcStatus
  enabled: boolean
  onStateChange: (macAddress: string, state: TileState) => void
  onSessionChange: (macAddress: string, sessionId: string | null) => void
}) {
  const targetRef = useRef<HTMLDivElement>(null)
  const rfbRef = useRef<RfbClient | null>(null)
  const sessionIdRef = useRef<string | null>(null)
  const closingRef = useRef(false)
  const [state, setState] = useState<TileState>(() => initialTileState(pc))
  const [message, setMessage] = useState('')
  const connectable = canStartScreenWallPc(pc)

  const updateState = useCallback((next: TileState, detail = '') => {
    setState(next)
    setMessage(detail)
    onStateChange(pc.macAddress, next)
  }, [onStateChange, pc.macAddress])

  useEffect(() => {
    const staticState = initialTileState(pc)
    if (!enabled || !connectable) {
      updateState(staticState)
      return
    }

    const controller = new AbortController()
    closingRef.current = false
    updateState('starting')

    const connect = async () => {
      let password = ''
      try {
        let session = await createRemoteControlSession(pc.macAddress)
        if (controller.signal.aborted || closingRef.current) {
          void closeRemoteControlSession(session.sessionId).catch(() => undefined)
          return
        }
        sessionIdRef.current = session.sessionId
        onSessionChange(pc.macAddress, session.sessionId)
        updateState('waiting')

        let pollDelay = SCREEN_WALL_STATUS_POLL_INITIAL_MS
        while (session.status === 'PENDING') {
          await wait(pollDelay, controller.signal)
          session = await getRemoteControlSession(session.sessionId)
          pollDelay = nextScreenWallPollDelay(pollDelay)
        }
        if (session.status === 'FAILED' || session.status === 'CLOSED') {
          throw new Error(session.failureReason || '에이전트가 화면을 준비하지 못했습니다.')
        }
        if (session.status !== 'READY') throw new Error(`세션 상태 오류: ${session.status}`)

        const ticket = await issueRemoteControlTicket(session.sessionId)
        if (controller.signal.aborted || !targetRef.current) return
        password = ticket.oneTimePassword
        updateState('connecting')
        const { default: RFB } = await import('@novnc/novnc')
        if (controller.signal.aborted || !targetRef.current) return
        targetRef.current.replaceChildren()
        const credentials = { username: '', password, target: '' }
        const client = new RFB(
          targetRef.current,
          remoteControlWebSocketUrl(ticket.webSocketPath),
          { credentials, shared: true },
        ) as unknown as RfbClient
        rfbRef.current = client
        client.viewOnly = true
        client.scaleViewport = true
        client.resizeSession = false
        client.focusOnClick = false
        client.qualityLevel = 2
        client.compressionLevel = 9
        client.addEventListener('credentialsrequired', () => {
          if (password) client.sendCredentials(credentials)
        })
        client.addEventListener('connect', () => {
          password = ''
          credentials.password = ''
          updateState('connected')
        })
        client.addEventListener('securityfailure', (event) => {
          const reason = (event as CustomEvent<{ reason?: string }>).detail?.reason
          updateState('failed', reason || '화면 인증 실패')
        })
        client.addEventListener('disconnect', () => {
          if (!closingRef.current) updateState('failed', '연결 끊김')
        })
      } catch (error) {
        if (controller.signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) return
        updateState('failed', error instanceof Error ? error.message : '연결 실패')
        const sessionId = sessionIdRef.current
        sessionIdRef.current = null
        onSessionChange(pc.macAddress, null)
        if (sessionId) void closeRemoteControlSession(sessionId).catch(() => undefined)
      }
    }

    const startTimer = window.setTimeout(() => void connect(), 0)
    return () => {
      window.clearTimeout(startTimer)
      controller.abort()
      closingRef.current = true
      try {
        rfbRef.current?.disconnect()
      } catch {
        // The relay may already be closed.
      }
      rfbRef.current = null
      const sessionId = sessionIdRef.current
      sessionIdRef.current = null
      onSessionChange(pc.macAddress, null)
      if (sessionId) void closeRemoteControlSession(sessionId).catch(() => undefined)
    }
  }, [connectable, enabled, onSessionChange, pc.macAddress, updateState])

  return (
    <article className={`screen-wall-tile state-${state}`}>
      <header>
        <strong title={pc.pcName}>{seatLabel(pc)}번 · {pc.pcName}</strong>
        <span>{tileStateLabel[state]}</span>
      </header>
      <div className="screen-wall-frame" ref={targetRef} aria-label={`${pc.pcName} 화면`} />
      {state !== 'connected' && (
        <div className={`screen-wall-tile-status ${state}`}>
          {['starting', 'waiting', 'connecting'].includes(state) && <LoaderCircle size={18} className="spin-icon" />}
          <span>{message || tileStateLabel[state]}</span>
        </div>
      )}
    </article>
  )
}

export function ClassroomScreenWall({ room, pcs, onClose }: Props) {
  const { token } = useAuth()
  const wallPcs = useMemo(() => screenWallPcs(pcs), [pcs])
  const [generation, setGeneration] = useState(0)
  const [visible, setVisible] = useState(() => document.visibilityState !== 'hidden')
  const [startCount, setStartCount] = useState(() => Math.min(SCREEN_WALL_BATCH_SIZE, wallPcs.length))
  const [states, setStates] = useState<Record<string, TileState>>({})
  const [fullscreen, setFullscreen] = useState(false)
  const sessionIdsRef = useRef(new Map<string, string>())
  const dialogRef = useDialogFocus<HTMLElement>(onClose)

  const onStateChange = useCallback((macAddress: string, state: TileState) => {
    setStates((current) => current[macAddress] === state ? current : { ...current, [macAddress]: state })
  }, [])

  const onSessionChange = useCallback((macAddress: string, sessionId: string | null) => {
    if (sessionId) sessionIdsRef.current.set(macAddress, sessionId)
    else sessionIdsRef.current.delete(macAddress)
  }, [])

  useEffect(() => {
    const onVisibilityChange = () => setVisible(document.visibilityState !== 'hidden')
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => document.removeEventListener('visibilitychange', onVisibilityChange)
  }, [])

  useEffect(() => {
    setStates({})
    setStartCount(visible ? Math.min(SCREEN_WALL_BATCH_SIZE, wallPcs.length) : 0)
    if (!visible) return
    const timer = window.setInterval(() => {
      setStartCount((current) => {
        const next = nextScreenWallStartCount(current, wallPcs.length)
        if (next >= wallPcs.length) window.clearInterval(timer)
        return next
      })
    }, SCREEN_WALL_BATCH_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [generation, visible, wallPcs.length])

  useEffect(() => {
    const onFullscreenChange = () => setFullscreen(document.fullscreenElement === dialogRef.current)
    document.addEventListener('fullscreenchange', onFullscreenChange)
    return () => document.removeEventListener('fullscreenchange', onFullscreenChange)
  }, [dialogRef])

  useEffect(() => {
    const onPageHide = () => {
      if (!token) return
      closeRemoteControlSessionsOnPageExit([...sessionIdsRef.current.values()], token)
    }
    window.addEventListener('pagehide', onPageHide)
    return () => window.removeEventListener('pagehide', onPageHide)
  }, [token])

  const connected = Object.values(states).filter((state) => state === 'connected').length
  const failed = Object.values(states).filter((state) => state === 'failed').length
  const eligible = wallPcs.filter(canStartScreenWallPc).length

  const toggleFullscreen = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen()
      else await dialogRef.current?.requestFullscreen()
    } catch {
      // Fullscreen can be denied by the browser or OS policy.
    }
  }

  return (
    <div className="screen-wall-backdrop" role="presentation">
      <section ref={dialogRef} className="screen-wall-dialog" role="dialog" aria-modal="true" aria-labelledby="screen-wall-title" tabIndex={-1}>
        <header className="screen-wall-header">
          <div>
            <h2 id="screen-wall-title">{room.groupName} 화면 모니터링</h2>
            <p>학생 PC {wallPcs.length}대 · 연결 {connected}/{eligible}{failed > 0 ? ` · 실패 ${failed}` : ''}</p>
          </div>
          <div className="screen-wall-tools">
            <button className="icon-button" onClick={() => setGeneration((current) => current + 1)} aria-label="전체 다시 연결" title="전체 다시 연결"><RefreshCw size={17} /></button>
            <button className="icon-button" onClick={() => void toggleFullscreen()} aria-label={fullscreen ? '전체화면 종료' : '전체화면'} title={fullscreen ? '전체화면 종료' : '전체화면'}>{fullscreen ? <Minimize2 size={17} /> : <Expand size={17} />}</button>
            <button className="icon-button close" onClick={onClose} aria-label="화면 모니터링 닫기" title="닫기"><X size={19} /></button>
          </div>
        </header>
        {!visible && <div className="screen-wall-paused">다른 탭을 보는 동안 연결을 정리했습니다. 이 탭으로 돌아오면 자동으로 다시 연결합니다.</div>}
        <div className="screen-wall-grid">
          {wallPcs.map((pc, index) => (
            <ScreenWallTile
              key={`${generation}-${pc.macAddress}`}
              pc={pc}
              enabled={visible && index < startCount}
              onStateChange={onStateChange}
              onSessionChange={onSessionChange}
            />
          ))}
          {!wallPcs.length && <div className="screen-wall-empty">등록된 학생 PC가 없습니다.</div>}
        </div>
      </section>
    </div>
  )
}
