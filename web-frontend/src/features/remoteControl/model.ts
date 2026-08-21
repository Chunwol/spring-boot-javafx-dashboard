import type { RemoteControlSession } from './types'

export function remoteControlWebSocketUrl(path: string, location: Pick<Location, 'origin' | 'protocol'> = window.location) {
  if (/^wss?:\/\//i.test(path)) return path
  const url = new URL(path, location.origin)
  url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}

export function remoteControlStatusLabel(status: RemoteControlSession['status']) {
  return ({
    PENDING: '에이전트 준비 대기',
    READY: '연결 준비 완료',
    TICKET_ISSUED: '보안 연결 준비',
    CONNECTING: '원격 화면 연결 중',
    CONNECTED: '원격 제어 연결됨',
    CLOSED: '원격 제어 종료',
    FAILED: '원격 제어 실패',
  } as const)[status]
}

export function isRemoteControlTerminal(status: RemoteControlSession['status']) {
  return status === 'FAILED' || status === 'CLOSED'
}
