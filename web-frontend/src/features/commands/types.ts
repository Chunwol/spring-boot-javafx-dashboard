export const COMMAND_STATUSES = [
  'QUEUED',
  'DELIVERED',
  'ACKED',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
] as const

export type CommandStatus = (typeof COMMAND_STATUSES)[number]

export const COMMAND_TYPES = [
  'SHUTDOWN',
  'REBOOT',
  'WOL',
  'RUN_CMD',
  'RUN_PS1',
  'COLLECT_FILES',
  'LIST_FILES',
  'AGENT_UPDATE',
  'AGENT_BUNDLE_UPDATE',
  'REMOTE_CONTROL_START',
  'REMOTE_CONTROL_STOP',
] as const

export type CommandType = (typeof COMMAND_TYPES)[number]
export type CommandScope = 'SINGLE' | 'GROUP'
export type CommandRole = 'ALL' | 'TEACHER' | 'STUDENT'

export interface CommandView {
  taskId: number
  macAddress: string
  commandType: string
  status: string
  payloadJson?: string | null
  scriptUrl?: string | null
  createdAt?: string | null
  deliveredAt?: string | null
  ackedAt?: string | null
  completedAt?: string | null
  exitCode?: number | null
  stdout?: string | null
  stderr?: string | null
  retryCount?: number | null
  isExecuted?: boolean | null
  verificationStatus?: string | null
  verificationStartedAt?: string | null
  verificationDeadlineAt?: string | null
  verificationObservedOfflineAt?: string | null
  verificationCompletedAt?: string | null
  scmateResetStatus?: string | null
  scmateResetRequestedAt?: string | null
  scmateResetCompletedAt?: string | null
  pcName?: string | null
  ipAddress?: string | null
  groupId?: number | null
  groupName?: string | null
  pcStatus?: string | null
  role?: string | null
}

export interface CommandPage {
  content: CommandView[]
  totalElements: number
  totalPages: number
  size: number
  number: number
  first: boolean
  last: boolean
  numberOfElements: number
  empty: boolean
}

export interface CommandListFilters {
  statuses: readonly CommandStatus[]
  taskId?: number
  page: number
  size: number
}

export type CommandStatusFilter =
  | 'ALL'
  | 'ACTIVE'
  | 'COMPLETED'
  | 'FAILED'
  | 'VERIFYING'
  | 'SCMATE_PENDING'
  | 'CANCELLED'

export type CommandSort = 'NEWEST' | 'OLDEST' | 'GROUP' | 'PC' | 'STATUS' | 'TYPE'

export interface CommandSnapshotFilters {
  search: string
  status: CommandStatusFilter
  hiddenTypes: ReadonlySet<string>
  group: string
  sort: CommandSort
}

export interface CommandSnapshotSummary {
  queued: number
  delivered: number
  acked: number
  completed: number
  failed: number
  cancelled: number
  verifying: number
  scmatePending: number
}

export interface CommandProgressStep {
  label: string
  state: 'done' | 'active' | 'pending' | 'failed'
}

export interface IssueCommandRequest {
  scope: CommandScope
  role?: CommandRole
  type: CommandType
  macAddress?: string
  groupId?: number
  payloadJson?: string
  scriptUrl?: string
  broadcastIp?: string
  isAdminOperation?: boolean
}

export interface CommandEnqueueResult {
  queued?: number
  taskIds?: number[]
  duplicateSkipped?: number
  offlineSkipped?: number
  leaderMac?: string
  leaderMacs?: string[]
  broadcastIp?: string
  message?: string
  routerSignals?: number
  routerAttempts?: number
  routerGateways?: string[]
  routerMessage?: string
}

export interface CommandDraft {
  scope: CommandScope
  role: CommandRole
  type: CommandType
  macAddress: string
  groupId: string
  payloadJson: string
  scriptUrl: string
  broadcastIp: string
}

export type CommandDraftErrors = Partial<Record<keyof CommandDraft, string>>
