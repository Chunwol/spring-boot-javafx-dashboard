export type RemoteControlStatus =
  | 'PENDING'
  | 'READY'
  | 'TICKET_ISSUED'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'CLOSED'
  | 'FAILED'

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
