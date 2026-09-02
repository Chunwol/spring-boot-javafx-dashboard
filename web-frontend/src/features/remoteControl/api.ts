import { api, getApiBaseUrl } from '../../api/client'

export type RemoteControlStatus = 'PENDING' | 'READY' | 'TICKET_ISSUED' | 'CONNECTING' | 'CONNECTED' | 'CLOSED' | 'FAILED'

export interface RemoteControlSession {
  sessionId: string
  taskId: number
  macAddress: string
  status: RemoteControlStatus
  expiresAt: string
  failureReason?: string | null
}

export interface RemoteControlTicket {
  sessionId: string
  taskId: number
  webSocketPath: string
  oneTimePassword: string
  ticketExpiresAt: string
}

export interface ActiveRemoteControlSession {
  sessionId: string
  username: string
  sourceIp?: string | null
  userAgent?: string | null
  macAddress: string
  pcName?: string | null
  targetIp?: string | null
  status: RemoteControlStatus
  startedAt: string
}

export function createRemoteControlSession(macAddress: string) {
  return api.post<RemoteControlSession>('/remote-control/sessions', { macAddress })
}

export function listActiveRemoteControlSessions() {
  return api.get<ActiveRemoteControlSession[]>('/remote-control/sessions/active')
}

export function getRemoteControlSession(sessionId: string) {
  return api.get<RemoteControlSession>(`/remote-control/sessions/${encodeURIComponent(sessionId)}`)
}

export function issueRemoteControlTicket(sessionId: string) {
  return api.post<RemoteControlTicket>(`/remote-control/sessions/${encodeURIComponent(sessionId)}/ticket`)
}

export function closeRemoteControlSession(sessionId: string) {
  return api.delete<void>(`/remote-control/sessions/${encodeURIComponent(sessionId)}`)
}

export function closeRemoteControlSessionOnPageExit(sessionId: string, token: string) {
  const base = getApiBaseUrl()
  const path = `/remote-control/sessions/${encodeURIComponent(sessionId)}`
  void fetch(`${base}${path}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
    keepalive: true,
  }).catch(() => undefined)
}

export function closeRemoteControlSessionsOnPageExit(sessionIds: string[], token: string) {
  if (!sessionIds.length) return
  const base = getApiBaseUrl()
  void fetch(`${base}/remote-control/sessions/batch-close`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ sessionIds }),
    keepalive: true,
  }).catch(() => undefined)
}

export function remoteControlWebSocketUrl(path: string, location: Pick<Location, 'href' | 'protocol'> = window.location) {
  const url = new URL(path, location.href)
  url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}
