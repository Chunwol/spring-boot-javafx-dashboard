import { api } from '../../api/client'
import type { CommandResult, PcStatus, ScmateRecoveryPoint } from '../../types/dashboard'
import type { PcPowerCommand, ScmateAction } from './model'

export interface PcCommandSummary {
  requested: number
  queued: number
  duplicateSkipped: number
  failed: number
  messages: string[]
}

export function getPcs() {
  return api.get<PcStatus[]>('/dashboard/pcs')
}

export function sendScmateAction(macAddress: string, action: ScmateAction, recoveryPoint: ScmateRecoveryPoint = 'BASELINE') {
  return api.post<{ message?: string } | undefined>('/scmate/actions', { macAddress, action, recoveryPoint })
}

export interface ScmateActionFailure {
  pcName: string
  message: string
}

export interface ScmateActionSummary {
  requested: number
  queued: number
  failed: number
  errors: ScmateActionFailure[]
}

interface ScmateBulkActionResult {
  macAddress: string
  accepted: boolean
  error?: string
}

interface ScmateBulkActionResponse {
  requested: number
  queued: number
  failed: number
  results: ScmateBulkActionResult[]
}

export async function sendScmateActions(
  pcs: PcStatus[],
  action: ScmateAction,
  recoveryPoint: ScmateRecoveryPoint = 'BASELINE',
): Promise<ScmateActionSummary> {
  const namesByMac = new Map(pcs.map((pc) => [pc.macAddress.toUpperCase(), pc.pcName || pc.macAddress]))
  const response = await api.post<ScmateBulkActionResponse>('/scmate/actions/bulk', pcs.map((pc) => ({
    macAddress: pc.macAddress,
    action,
    recoveryPoint,
  })))
  const errors = response.results
    .filter((result) => !result.accepted)
    .map((result) => ({
      pcName: namesByMac.get(result.macAddress.toUpperCase()) || result.macAddress,
      message: result.error?.trim() || 'SCMATE 작업을 요청하지 못했습니다.',
    }))
  return {
    requested: response.requested,
    queued: response.queued,
    failed: response.failed,
    errors,
  }
}

export async function sendPcCommands(pcs: PcStatus[], type: PcPowerCommand): Promise<PcCommandSummary> {
  const summary: PcCommandSummary = {
    requested: pcs.length,
    queued: 0,
    duplicateSkipped: 0,
    failed: 0,
    messages: [],
  }
  const messages = new Set<string>()

  if (type !== 'PCON') {
    const result = await api.post<CommandResult>('/commands', {
      scope: 'MULTI',
      targetMacs: pcs.map((pc) => pc.macAddress),
      type,
    })
    summary.queued = result.queued || 0
    summary.duplicateSkipped = result.duplicateSkipped || 0
    summary.failed = Math.max(0, pcs.length - summary.queued - summary.duplicateSkipped)
    if (result.message) messages.add(result.message)
  } else {
    const byGroup = new Map<number, PcStatus[]>()
    for (const pc of pcs) {
      if (pc.groupId == null) {
        summary.failed += 1
        continue
      }
      const group = byGroup.get(pc.groupId) || []
      group.push(pc)
      byGroup.set(pc.groupId, group)
    }
    const groups = [...byGroup.entries()]
    const results = await Promise.allSettled(groups.map(([groupId, targets]) =>
      api.post<CommandResult>('/commands', {
        scope: 'GROUP',
        groupId,
        role: 'ALL',
        targetMacs: targets.map((pc) => pc.macAddress),
        type,
      }).then((result) => ({ result, requested: targets.length }))))

    for (const [index, settled] of results.entries()) {
      if (settled.status === 'rejected') {
        summary.failed += groups[index][1].length
        continue
      }
      const { result, requested } = settled.value
      const accepted = result.acceptedTargets ?? (result.queued ? requested : 0)
      const duplicates = result.duplicateTargets ?? 0
      summary.queued += accepted
      summary.duplicateSkipped += duplicates
      summary.failed += result.failedTargets ?? Math.max(0, requested - accepted - duplicates)
      if (result.message) messages.add(result.message)
    }
  }

  summary.messages = [...messages]
  return summary
}
