import { strToU8, zip } from 'fflate'
import { api } from '../../api/client'
import type { CommandResult, PcStatus } from '../../types/dashboard'

export type OperationCommandType = 'RUN_PS1' | 'COLLECT_FILES' | 'LIST_FILES'

export interface OperationCommandRequest {
  type: OperationCommandType
  payload?: Record<string, unknown>
  scriptUrl?: string
}

export interface OperationSummary {
  requested: number
  queued: number
  failed: number
  duplicateSkipped: number
  offlineSkipped: number
  notFoundSkipped: number
  taskIds: number[]
}

export interface RemoteFileEntry {
  name: string
  type: 'drive' | 'dir' | 'file'
  size: number
  displayName?: string
  usb?: boolean
  freeBytes?: number
  totalBytes?: number
}

export interface RemoteFileListing {
  currentPath: string
  parentPath: string
  entries: RemoteFileEntry[]
  hasMore: boolean
  nextOffset: number
  partial: boolean
  warnings: string[]
}

interface CommandDetail {
  taskId: number
  status: string
  stdout?: string | null
  stderr?: string | null
}

export interface OperationStatus extends CommandDetail {
  macAddress: string
  commandType: string
  pcName?: string | null
  groupName?: string | null
  payloadJson?: string | null
  scriptUrl?: string | null
}

const FINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
const MULTI_FILE_LIMIT = 128 * 1024 * 1024

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

function normalizedArchivePath(file: File, used: Set<string>) {
  const raw = file.webkitRelativePath || file.name
  const safe = raw.replace(/\\/g, '/').replace(/^\/+/, '').replace(/(?:^|\/)\.\.(?:\/|$)/g, '/') || file.name
  let result = safe
  let suffix = 2
  const dot = safe.lastIndexOf('.')
  while (used.has(result.toLocaleLowerCase())) {
    result = dot > safe.lastIndexOf('/')
      ? `${safe.slice(0, dot)}-${suffix}${safe.slice(dot)}`
      : `${safe}-${suffix}`
    suffix += 1
  }
  used.add(result.toLocaleLowerCase())
  return result
}

export async function createTransferArtifact(files: readonly File[]) {
  if (!files.length) throw new Error('전송할 파일 또는 폴더를 선택하세요.')
  if (files.length === 1 && !files[0].webkitRelativePath) {
    return { file: files[0], zipped: false, sourceType: 'file' as const }
  }

  const total = files.reduce((sum, file) => sum + file.size, 0)
  if (total > MULTI_FILE_LIMIT) {
    throw new Error('브라우저 메모리 보호를 위해 여러 파일/폴더는 128MB까지만 자동 ZIP합니다. 더 큰 폴더는 Windows에서 먼저 ZIP으로 압축한 뒤 "파일 선택"으로 ZIP 하나를 선택하세요.')
  }

  const used = new Set<string>()
  const input: Record<string, Uint8Array> = {}
  for (const file of files) {
    const path = normalizedArchivePath(file, used)
    input[path] = file.size ? new Uint8Array(await file.arrayBuffer()) : strToU8('')
  }

  const bytes = await new Promise<Uint8Array>((resolve, reject) => {
    zip(input, { level: 6 }, (error, data) => error ? reject(error) : resolve(data))
  })
  for (const path of Object.keys(input)) delete input[path]
  const filename = files.some((file) => file.webkitRelativePath)
    ? `${(files[0].webkitRelativePath.split('/')[0] || 'folder').replace(/[<>:"/\\|?*]/g, '_')}.zip`
    : `files-${new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)}.zip`
  const archiveBuffer = bytes.buffer as ArrayBuffer
  const fileBuffer = bytes.byteOffset === 0 && bytes.byteLength === archiveBuffer.byteLength
    ? archiveBuffer
    : archiveBuffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)
  return {
    file: new File([fileBuffer], filename, { type: 'application/zip', lastModified: Date.now() }),
    zipped: true,
    sourceType: 'folder' as const,
  }
}

export function joinRemotePath(parent: string, name: string) {
  if (!parent.trim()) return name
  const separator = parent.includes('\\') || /^[A-Za-z]:/.test(parent) ? '\\' : '/'
  return `${parent.replace(/[\\/]$/, '')}${separator}${name.replace(/^[\\/]/, '')}`
}

export function parseRemoteListing(raw: string | null | undefined): RemoteFileListing {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw || '')
  } catch {
    throw new Error('에이전트가 올바른 파일 목록을 반환하지 않았습니다.')
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('에이전트 파일 목록 형식이 올바르지 않습니다.')
  }
  const row = parsed as Record<string, unknown>
  if (row.ok !== true) throw new Error(typeof row.error === 'string' ? row.error : '원격 파일 목록 조회에 실패했습니다.')
  const entries = Array.isArray(row.entries) ? row.entries.flatMap((entry): RemoteFileEntry[] => {
    if (!entry || typeof entry !== 'object') return []
    const item = entry as Record<string, unknown>
    const name = typeof item.name === 'string' ? item.name.trim() : ''
    const type = item.type === 'drive' || item.type === 'dir' ? item.type : 'file'
    if (!name) return []
    return [{
      name,
      type,
      size: typeof item.size === 'number' ? item.size : 0,
      displayName: typeof item.displayName === 'string' ? item.displayName : undefined,
      usb: item.usb === true,
      freeBytes: typeof item.freeBytes === 'number' ? item.freeBytes : undefined,
      totalBytes: typeof item.totalBytes === 'number' ? item.totalBytes : undefined,
    }]
  }) : []
  return {
    currentPath: typeof row.currentPath === 'string' ? row.currentPath : '',
    parentPath: typeof row.parentPath === 'string' ? row.parentPath : '',
    entries,
    hasMore: row.hasMore === true,
    nextOffset: typeof row.nextOffset === 'number' ? row.nextOffset : entries.length,
    partial: row.partial === true,
    warnings: Array.isArray(row.warningsPreview) ? row.warningsPreview.filter((value): value is string => typeof value === 'string') : [],
  }
}

async function enqueue(pc: PcStatus, request: OperationCommandRequest) {
  return api.post<CommandResult>('/commands', {
    scope: 'SINGLE',
    macAddress: pc.macAddress,
    type: request.type,
    scriptUrl: request.scriptUrl,
    payloadJson: JSON.stringify(request.payload || {}),
  })
}

export async function sendPcOperation(pcs: readonly PcStatus[], request: OperationCommandRequest): Promise<OperationSummary> {
  const targetMacs = [...new Set(pcs.map((pc) => pc.macAddress).filter(Boolean))]
  if (!targetMacs.length) {
    return { requested: 0, queued: 0, failed: 0, duplicateSkipped: 0, offlineSkipped: 0, notFoundSkipped: 0, taskIds: [] }
  }

  const result = await api.post<CommandResult>('/commands', {
    scope: 'MULTI',
    targetMacs,
    type: request.type,
    scriptUrl: request.scriptUrl,
    payloadJson: JSON.stringify(request.payload || {}),
  })
  const queued = result.queued || 0
  const duplicateSkipped = result.duplicateSkipped || 0
  const offlineSkipped = result.offlineSkipped || 0
  const notFoundSkipped = result.notFoundSkipped || 0
  const requested = result.requested ?? targetMacs.length
  return {
    requested,
    queued,
    duplicateSkipped,
    offlineSkipped,
    notFoundSkipped,
    failed: Math.max(0, requested - queued - duplicateSkipped - offlineSkipped - notFoundSkipped),
    taskIds: result.taskIds || [],
  }
}

export async function listRemoteFiles(pc: PcStatus, path: string, offset = 0, timeoutMs = 60_000) {
  const result = await enqueue(pc, { type: 'LIST_FILES', payload: { path, offset, limit: 200 } })
  const taskId = result.taskIds?.[0]
  if (!taskId) throw new Error('원격 파일 목록 명령을 등록하지 못했습니다.')

  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const detail = await api.get<CommandDetail>(`/commands?taskId=${taskId}`)
    const status = detail.status?.toUpperCase()
    if (status === 'COMPLETED') return parseRemoteListing(detail.stdout)
    if (FINAL_STATUSES.has(status)) throw new Error(detail.stderr?.trim() || `원격 파일 목록 조회가 ${status} 상태로 종료되었습니다.`)
    await sleep(800)
  }
  throw new Error('원격 파일 목록 응답 시간이 초과되었습니다.')
}

export function getOperationStatuses(taskIds: readonly number[]) {
  const uniqueIds = [...new Set(taskIds)].slice(0, 2000)
  if (!uniqueIds.length) return Promise.resolve([] as OperationStatus[])
  return api.post<OperationStatus[]>('/commands/status', { taskIds: uniqueIds })
}
