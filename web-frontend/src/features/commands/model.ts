import {
  COMMAND_STATUSES,
  COMMAND_TYPES,
  type CommandDraft,
  type CommandDraftErrors,
  type CommandListFilters,
  type CommandProgressStep,
  type CommandSnapshotFilters,
  type CommandSnapshotSummary,
  type CommandStatus,
  type CommandType,
  type CommandView,
  type IssueCommandRequest,
} from './types'

const MAC_PATTERN = /^[0-9A-F]{2}(?:[:-][0-9A-F]{2}){5}$/i
const IPV4_PATTERN = /^(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)$/
const JSON_REQUIRED_TYPES = new Set<CommandType>(['RUN_CMD', 'COLLECT_FILES', 'AGENT_UPDATE', 'AGENT_BUNDLE_UPDATE'])

export const DEFAULT_COMMAND_DRAFT: CommandDraft = {
  scope: 'SINGLE',
  role: 'ALL',
  type: 'SHUTDOWN',
  macAddress: '',
  groupId: '',
  payloadJson: '',
  scriptUrl: '',
  broadcastIp: '',
}

export const COMMAND_STATUS_LABELS: Record<CommandStatus, string> = {
  QUEUED: '대기',
  DELIVERED: '전달',
  ACKED: '실행 중',
  COMPLETED: '완료',
  FAILED: '실패',
  CANCELLED: '취소',
}

export const COMMAND_TYPE_LABELS: Record<CommandType, string> = {
  SHUTDOWN: '종료',
  REBOOT: '재시작',
  WOL: '켜기 (WOL)',
  RUN_CMD: '명령 프롬프트 실행',
  RUN_PS1: 'PowerShell 실행',
  COLLECT_FILES: '파일 수집',
  LIST_FILES: '파일 목록 조회',
  AGENT_UPDATE: '에이전트 업데이트',
  AGENT_BUNDLE_UPDATE: '에이전트 전체 번들 업데이트',
  REMOTE_CONTROL_START: '원격 제어 시작',
  REMOTE_CONTROL_STOP: '원격 제어 종료',
}

export const COMMAND_STATUS_PRESETS = {
  ALL: COMMAND_STATUSES,
  ACTIVE: ['QUEUED', 'DELIVERED', 'ACKED'],
  COMPLETED: ['COMPLETED'],
  FAILED: ['FAILED'],
  CANCELLED: ['CANCELLED'],
} as const satisfies Record<string, readonly CommandStatus[]>

export type CommandStatusPreset = keyof typeof COMMAND_STATUS_PRESETS

export function commandStatusLabel(status: string | null | undefined) {
  const normalized = status?.trim().toUpperCase() as CommandStatus | undefined
  return normalized && normalized in COMMAND_STATUS_LABELS
    ? COMMAND_STATUS_LABELS[normalized]
    : status || '-'
}

export function commandStatusTone(status: string | null | undefined) {
  switch (status?.trim().toUpperCase()) {
    case 'COMPLETED': return 'completed'
    case 'FAILED': return 'failed'
    case 'CANCELLED': return 'cancelled'
    case 'ACKED': return 'running'
    case 'DELIVERED': return 'delivered'
    default: return 'queued'
  }
}

export function commandTypeLabel(type: string | null | undefined) {
  const normalized = type?.trim().toUpperCase()
  if (normalized === 'WOL_RELAY' || normalized === 'PCON') return COMMAND_TYPE_LABELS.WOL
  return normalized && COMMAND_TYPES.includes(normalized as CommandType)
    ? COMMAND_TYPE_LABELS[normalized as CommandType]
    : type || '-'
}

export function commandTargetLabel(command: { pcName?: string | null; macAddress?: string | null }) {
  return command.pcName?.trim() || command.macAddress?.trim() || '-'
}

function normalize(value: string | null | undefined) {
  return value?.trim().toUpperCase() || ''
}

export function parseCommandPayload(command: { payloadJson?: string | null }) {
  if (!command.payloadJson?.trim()) return null
  try {
    return parseJsonObject(command.payloadJson)
  } catch {
    return null
  }
}

export function commandDisplayType(command: Pick<CommandView, 'commandType' | 'payloadJson'>) {
  const type = normalize(command.commandType)
  if (type === 'COLLECT_FILES') return 'FILE_RX'
  if (type !== 'RUN_PS1') return type || '-'
  return parseCommandPayload(command)?.transferMode === true ? 'FILE_TX' : 'RUN_PS1'
}

export function commandRunMode(command: Pick<CommandView, 'commandType' | 'payloadJson'>) {
  if (normalize(command.commandType) !== 'RUN_PS1') return '-'
  const payload = parseCommandPayload(command)
  const runMode = payload?.runMode === 'BACKGROUND' ? '즉시 실행' : '대기 실행'
  const windowMode = payload?.windowMode === 'VISIBLE' ? '창 표시' : '창 숨김'
  if (payload?.transferMode === true) {
    return `${payload.runAfterTransfer === true ? '전송 후 실행' : '전송만'} · ${runMode} · ${windowMode}`
  }
  return `${runMode} · ${windowMode}`
}

export function commandTransferDisplay(command: Pick<CommandView, 'commandType' | 'payloadJson'>) {
  const type = normalize(command.commandType)
  const payload = parseCommandPayload(command)
  if (type === 'COLLECT_FILES') {
    const paths = Array.isArray(payload?.paths) ? payload.paths.length : payload?.path ? 1 : 0
    const outputName = typeof payload?.outputName === 'string' ? payload.outputName.trim() : ''
    const base = paths ? `경로 ${paths}개 수집` : '수집'
    return outputName ? `${base} · ${outputName}` : base
  }
  if (type !== 'RUN_PS1' || payload?.transferMode !== true) return '-'
  const source = payload.sourceType === 'folder' ? '폴더' : '파일'
  const name = typeof payload.displayName === 'string' && payload.displayName.trim() ? payload.displayName.trim() : '-'
  return `${source} · ${name}`
}

export function commandDestinationDisplay(command: Pick<CommandView, 'commandType' | 'payloadJson'>) {
  const type = normalize(command.commandType)
  if (type === 'COLLECT_FILES') return '백엔드 업로드 → 브라우저 다운로드'
  const payload = parseCommandPayload(command)
  if (type !== 'RUN_PS1' || payload?.transferMode !== true) return '-'
  if (payload.destinationMode === 'DESKTOP') return '바탕화면'
  if (payload.destinationMode === 'CUSTOM') {
    return typeof payload.destinationPath === 'string' && payload.destinationPath.trim()
      ? payload.destinationPath.trim()
      : '사용자 지정(미입력)'
  }
  return '다운로드 폴더'
}

export function commandVerificationLabel(command: Pick<CommandView, 'commandType' | 'verificationStatus'>) {
  switch (normalize(command.verificationStatus)) {
    case 'PENDING_OFFLINE': return normalize(command.commandType) === 'REBOOT' ? '재부팅 종료 확인 중' : '종료 확인 중'
    case 'PENDING_ONLINE': return '재부팅 재접속 확인 중'
    case 'PENDING_AGENT_RESTART':
    case 'PENDING_REBOOT_CONFIRM': return '에이전트 재시작 확인 중'
    case 'CONFIRMED': return ['AGENT_UPDATE', 'AGENT_BUNDLE_UPDATE'].includes(normalize(command.commandType))
      ? '업데이트 확인 완료'
      : normalize(command.commandType) === 'REBOOT' ? '재부팅 확인 완료' : '종료 확인 완료'
    case 'FAILED': return '검증 실패'
    default: return command.verificationStatus || '-'
  }
}

export function detailedCommandStatus(command: CommandView) {
  const verification = normalize(command.verificationStatus)
  if (['PENDING_OFFLINE', 'PENDING_ONLINE', 'PENDING_AGENT_RESTART', 'PENDING_REBOOT_CONFIRM'].includes(verification)) {
    return commandVerificationLabel(command)
  }
  if (verification === 'CONFIRMED') return commandVerificationLabel(command)
  if (normalize(command.scmateResetStatus) === 'PENDING') return '실행 완료 (SCMATE 시점 저장 필요)'
  if (normalize(command.scmateResetStatus) === 'COMPLETED') return '명령 완료'
  if (normalize(command.status) === 'FAILED') {
    const firstLine = command.stderr?.trim().split(/\r?\n/, 1)[0]
    return firstLine ? `실패 · ${firstLine.slice(0, 80)}${firstLine.length > 80 ? '...' : ''}` : '실패'
  }
  return commandStatusLabel(command.status)
}

export function commandProgressSteps(command: Pick<CommandView, 'status'>): CommandProgressStep[] {
  const labels = ['대기', '전달', '실행', '완료']
  const status = normalize(command.status)
  const current = status === 'DELIVERED' ? 1 : status === 'ACKED' ? 2 : ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status) ? 3 : 0
  return labels.map((label, index) => ({
    label: status === 'CANCELLED' && index === 3 ? '취소' : label,
    state: status === 'FAILED' && index === current
      ? 'failed'
      : index < current
        ? 'done'
        : index === current
          ? 'active'
          : 'pending',
  }))
}

function commandTime(command: CommandView) {
  for (const value of [command.completedAt, command.ackedAt, command.deliveredAt, command.createdAt]) {
    const parsed = value ? new Date(value).getTime() : Number.NaN
    if (!Number.isNaN(parsed)) return parsed
  }
  return 0
}

function groupLabel(command: CommandView) {
  return command.groupName?.trim() || (command.groupId != null ? `그룹 ${command.groupId}` : '-')
}

function statusRank(command: CommandView) {
  if (['PENDING_OFFLINE', 'PENDING_ONLINE', 'PENDING_AGENT_RESTART', 'PENDING_REBOOT_CONFIRM'].includes(normalize(command.verificationStatus))) return 0
  if (normalize(command.scmateResetStatus) === 'PENDING') return 1
  return ({ ACKED: 2, DELIVERED: 3, QUEUED: 4, FAILED: 5, COMPLETED: 6, CANCELLED: 7 } as Record<string, number>)[normalize(command.status)] ?? 9
}

function matchesStatus(command: CommandView, filter: CommandSnapshotFilters['status']) {
  const status = normalize(command.status)
  const verification = normalize(command.verificationStatus)
  if (filter === 'ACTIVE') return ['QUEUED', 'DELIVERED', 'ACKED'].includes(status)
  if (filter === 'COMPLETED') return status === 'COMPLETED' && !verification.startsWith('PENDING') && normalize(command.scmateResetStatus) !== 'PENDING'
  if (filter === 'FAILED') return status === 'FAILED'
  if (filter === 'VERIFYING') return ['PENDING_OFFLINE', 'PENDING_ONLINE', 'PENDING_AGENT_RESTART', 'PENDING_REBOOT_CONFIRM'].includes(verification)
  if (filter === 'SCMATE_PENDING') return normalize(command.scmateResetStatus) === 'PENDING'
  if (filter === 'CANCELLED') return status === 'CANCELLED'
  return true
}

export function filterAndSortCommands(commands: readonly CommandView[], filters: CommandSnapshotFilters) {
  const query = filters.search.trim().toLocaleLowerCase('ko-KR')
  const compared = (a: string, b: string) => a.localeCompare(b, 'ko-KR', { numeric: true, sensitivity: 'base' })
  const filtered = commands.filter((command) => {
    if (!matchesStatus(command, filters.status)) return false
    if (filters.hiddenTypes.has(commandDisplayType(command))) return false
    if (filters.group !== 'ALL' && groupLabel(command) !== filters.group) return false
    if (!query) return true
    return [
      groupLabel(command), command.pcName, command.ipAddress, command.macAddress,
      commandDisplayType(command), commandTypeLabel(command.commandType), detailedCommandStatus(command),
      commandRunMode(command), commandTransferDisplay(command), commandDestinationDisplay(command), command.scriptUrl,
      command.requestedBy, command.requestedIp,
    ].filter(Boolean).join(' ').toLocaleLowerCase('ko-KR').includes(query)
  })
  return [...filtered].sort((left, right) => {
    let result = 0
    if (filters.sort === 'OLDEST') result = commandTime(left) - commandTime(right)
    else if (filters.sort === 'GROUP') result = compared(groupLabel(left), groupLabel(right)) || commandTime(right) - commandTime(left)
    else if (filters.sort === 'PC') result = compared(commandTargetLabel(left), commandTargetLabel(right)) || commandTime(right) - commandTime(left)
    else if (filters.sort === 'STATUS') result = statusRank(left) - statusRank(right) || commandTime(right) - commandTime(left)
    else if (filters.sort === 'TYPE') result = compared(commandDisplayType(left), commandDisplayType(right)) || commandTime(right) - commandTime(left)
    else result = commandTime(right) - commandTime(left)
    return result || right.taskId - left.taskId
  })
}

export function summarizeCommands(commands: readonly CommandView[]): CommandSnapshotSummary {
  const summary: CommandSnapshotSummary = {
    queued: 0, delivered: 0, acked: 0, completed: 0, failed: 0, cancelled: 0, verifying: 0, scmatePending: 0,
  }
  for (const command of commands) {
    const status = normalize(command.status).toLocaleLowerCase() as keyof Pick<CommandSnapshotSummary, 'queued' | 'delivered' | 'acked' | 'completed' | 'failed' | 'cancelled'>
    if (status in summary) summary[status] += 1
    if (['PENDING_OFFLINE', 'PENDING_ONLINE', 'PENDING_AGENT_RESTART', 'PENDING_REBOOT_CONFIRM'].includes(normalize(command.verificationStatus))) summary.verifying += 1
    if (normalize(command.scmateResetStatus) === 'PENDING') summary.scmatePending += 1
  }
  return summary
}

export function formatCommandDate(value: string | null | undefined) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR')
}

export function isFinalCommandStatus(status: string | null | undefined) {
  return ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status?.trim().toUpperCase() || '')
}

export function canCancelCommand(status: string | null | undefined) {
  return status?.trim().toUpperCase() === 'QUEUED'
}

export function collectDownloadPath(command: { commandType?: string | null; scriptUrl?: string | null; stdout?: string | null }) {
  if (command.commandType?.trim().toUpperCase() !== 'COLLECT_FILES') return null
  for (const value of [command.scriptUrl, command.stdout]) {
    const match = value?.match(/\/api\/v1\/agent\/uploads\/\d+\/download\b/)
    if (match) return match[0].replace(/^\/api\/v1/, '')
  }
  return null
}

export function collectDownloadFilename(command: { taskId?: number | null; payloadJson?: string | null }) {
  try {
    const payload = command.payloadJson ? parseJsonObject(command.payloadJson) : null
    const outputName = typeof payload?.outputName === 'string' ? payload.outputName.trim() : ''
    if (outputName) {
      const basename = outputName.split(/[\\/]/).pop() || ''
      if (basename) return basename.toLowerCase().endsWith('.zip') ? basename : `${basename}.zip`
    }
  } catch {
    // A malformed historical payload must not hide an otherwise valid result download.
  }
  return command.taskId ? `collected-${command.taskId}.zip` : 'collected.zip'
}

function parseJsonObject(value: string) {
  const parsed = JSON.parse(value) as unknown
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('JSON 객체만 입력할 수 있습니다.')
  }
  return parsed as Record<string, unknown>
}

function isSafeScriptUrl(value: string) {
  if (value.startsWith('/') && !value.startsWith('//')) return true
  try {
    return new URL(value).protocol === 'https:'
  } catch {
    return false
  }
}

export function validateCommandDraft(draft: CommandDraft): CommandDraftErrors {
  const errors: CommandDraftErrors = {}
  if (!COMMAND_TYPES.includes(draft.type)) errors.type = '지원하지 않는 명령입니다.'

  if (draft.scope === 'SINGLE') {
    if (!MAC_PATTERN.test(draft.macAddress.trim())) {
      errors.macAddress = 'MAC 주소를 AA:BB:CC:DD:EE:FF 또는 AA-BB-CC-DD-EE-FF 형식으로 입력하세요.'
    }
  } else {
    const groupId = Number(draft.groupId)
    if (!Number.isInteger(groupId) || groupId <= 0) errors.groupId = '1 이상의 강의실 ID를 입력하세요.'
  }

  const payload = draft.payloadJson.trim()
  if (payload.length > 4_000) errors.payloadJson = 'JSON은 4000자를 초과할 수 없습니다.'
  if (!payload && JSON_REQUIRED_TYPES.has(draft.type)) {
    errors.payloadJson = '이 명령에는 JSON이 필요합니다.'
  } else if (payload) {
    try {
      const parsed = parseJsonObject(payload)
      if (draft.type === 'RUN_CMD' && (typeof parsed.cmd !== 'string' || !parsed.cmd.trim())) {
        errors.payloadJson = 'RUN_CMD JSON에는 비어 있지 않은 cmd가 필요합니다.'
      }
      if (draft.type === 'AGENT_UPDATE' || draft.type === 'AGENT_BUNDLE_UPDATE') {
        if (typeof parsed.downloadUrl !== 'string' || !isSafeScriptUrl(parsed.downloadUrl)) {
          errors.payloadJson = '업데이트 downloadUrl은 HTTPS 또는 상대 경로여야 합니다.'
        }
        if (typeof parsed.sha256 !== 'string' || !/^[0-9a-f]{64}$/i.test(parsed.sha256)) {
          errors.payloadJson = '업데이트 sha256은 64자리 16진수여야 합니다.'
        }
      }
    } catch (error) {
      errors.payloadJson = error instanceof Error ? error.message : '올바른 JSON을 입력하세요.'
    }
  }

  const scriptUrl = draft.scriptUrl.trim()
  if (scriptUrl.length > 500) errors.scriptUrl = '스크립트 URL은 500자를 초과할 수 없습니다.'
  if (draft.type === 'RUN_PS1' && !scriptUrl) errors.scriptUrl = 'RUN_PS1에는 스크립트 URL이 필요합니다.'
  else if (scriptUrl && !isSafeScriptUrl(scriptUrl)) errors.scriptUrl = 'HTTPS 또는 상대 경로 URL만 입력할 수 있습니다.'

  const broadcastIp = draft.broadcastIp.trim()
  if (broadcastIp && !IPV4_PATTERN.test(broadcastIp)) errors.broadcastIp = '올바른 IPv4 주소를 입력하세요.'
  return errors
}

export function toIssueCommandRequest(draft: CommandDraft): IssueCommandRequest {
  const request: IssueCommandRequest = {
    scope: draft.scope,
    type: draft.type,
  }
  if (draft.scope === 'SINGLE') request.macAddress = draft.macAddress.trim().toUpperCase()
  else {
    request.groupId = Number(draft.groupId)
    request.role = draft.role
  }
  if (draft.payloadJson.trim()) request.payloadJson = draft.payloadJson.trim()
  if (draft.scriptUrl.trim()) request.scriptUrl = draft.scriptUrl.trim()
  if (draft.type === 'WOL' && draft.broadcastIp.trim()) request.broadcastIp = draft.broadcastIp.trim()
  if (draft.type === 'AGENT_UPDATE' || draft.type === 'AGENT_BUNDLE_UPDATE') request.isAdminOperation = true
  return request
}

export function commandListKey(filters: CommandListFilters) {
  return JSON.stringify({
    statuses: [...filters.statuses],
    taskId: filters.taskId || null,
    page: filters.page,
    size: filters.size,
  })
}

export function issueCommandKey(request: IssueCommandRequest) {
  return JSON.stringify({
    scope: request.scope,
    role: request.role || null,
    type: request.type,
    macAddress: request.macAddress || null,
    groupId: request.groupId || null,
    payloadJson: request.payloadJson || null,
    scriptUrl: request.scriptUrl || null,
    broadcastIp: request.broadcastIp || null,
    isAdminOperation: request.isAdminOperation || false,
  })
}
