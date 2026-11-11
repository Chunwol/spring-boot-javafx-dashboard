export interface ClassroomSummary {
  groupId: number
  groupName: string
  onlinePcs: number
  totalPcs: number
  teacherPcStatus?: string | null
  runningCommandCount: number
  lastHeartbeat?: string | null
  ipRanges?: string[]
  visible?: boolean
}

export type ScmateRecoveryPoint = 'BASELINE' | 'SNAP1' | 'SNAP2'

export interface PcStatus {
  macAddress: string
  pcName: string
  ipAddress?: string | null
  groupId?: number | null
  groupName?: string | null
  online: boolean
  lastSeen?: string | null
  status?: string | null
  role?: 'TEACHER' | 'STUDENT' | string | null
  agentVersion?: string | null
  bundleVersion?: string | null
  brokenNote?: string | null
  deviceNote?: string | null
  scmateAvailable?: boolean | null
  scmateMode?: 'PROTECTED' | 'OPERATOR' | 'UNKNOWN' | null
  scmateTransitionPending?: boolean | null
  scmateRawState?: number | null
  scmateObservedAt?: string | null
  scmateBaselineUpdatedAt?: string | null
  scmateError?: string | null
  scmateWorkflowStage?: string | null
  scmateWorkflowId?: string | null
  scmateAllowedActions?: string[] | null
  scmateAvailabilityReason?: string | null
  scmateSchedule?: string | null
  scmateSelectedPoint?: ScmateRecoveryPoint | string | null
  scmateProductVariant?: string | null
  scmateStateSource?: string | null
  systemBootTimeEpochSeconds?: number | null
  scmateBaselineCreatedAt?: string | null
  scmateSnap1CreatedAt?: string | null
  scmateSnap2CreatedAt?: string | null
}

export interface CommandResult {
  queued?: number
  duplicateSkipped?: number
  offlineSkipped?: number
  acceptedTargets?: number
  duplicateTargets?: number
  failedTargets?: number
  requested?: number
  notFoundSkipped?: number
  message?: string
  taskIds?: number[]
  routerSignals?: number
  routerAttempts?: number
  routerGateways?: string[]
  routerMessage?: string
}

export type RoomStatus = 'online' | 'running' | 'warning' | 'offline'
