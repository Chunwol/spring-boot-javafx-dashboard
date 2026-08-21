import type {
  DistributionJobEvent,
  DistributionJobStatus,
  DistributionJobSummary,
  DistributionWaveStatus,
  UploadSession,
} from './types'

export const FILE_CHUNK_SIZE = 8 * 1024 * 1024
const UPLOAD_SESSIONS_KEY = 'classroom-dashboard.file-upload-sessions.v1'
const MAX_SAVED_SESSIONS = 10

function numberValue(value: unknown, fallback = 0) {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function stringValue(value: unknown, fallback = '') {
  return typeof value === 'string' ? value : value == null ? fallback : String(value)
}

function nullableString(value: unknown) {
  const text = stringValue(value).trim()
  return text || null
}

export function totalChunksFor(fileSize: number, chunkSize = FILE_CHUNK_SIZE) {
  if (!Number.isFinite(fileSize) || fileSize <= 0) return 0
  if (!Number.isFinite(chunkSize) || chunkSize <= 0) throw new Error('청크 크기는 0보다 커야 합니다.')
  return Math.ceil(fileSize / chunkSize)
}

export function fileFingerprint(file: Pick<File, 'name' | 'size' | 'lastModified'>) {
  return `${file.name}\u0000${file.size}\u0000${file.lastModified}`
}

export function createUploadId(now = Date.now(), randomId = crypto.randomUUID()) {
  return `web-${now}-${randomId}`
}

function readSessions(storage: Storage): UploadSession[] {
  try {
    const parsed = JSON.parse(storage.getItem(UPLOAD_SESSIONS_KEY) || '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.filter((item): item is UploadSession => Boolean(
      item && typeof item === 'object' && typeof item.fingerprint === 'string'
      && typeof item.uploadId === 'string' && typeof item.updatedAt === 'number',
    ))
  } catch {
    return []
  }
}

export function resolveUploadSession(
  file: Pick<File, 'name' | 'size' | 'lastModified'>,
  storage: Storage,
  now = Date.now(),
  uploadIdFactory = () => createUploadId(now),
) {
  const fingerprint = fileFingerprint(file)
  const sessions = readSessions(storage)
  const existing = sessions.find((session) => session.fingerprint === fingerprint)
  if (existing && existing.totalChunks === totalChunksFor(file.size)) {
    return { session: existing, resumed: true }
  }

  const session: UploadSession = {
    fingerprint,
    uploadId: uploadIdFactory(),
    originalFilename: file.name,
    fileSize: file.size,
    lastModified: file.lastModified,
    totalChunks: totalChunksFor(file.size),
    updatedAt: now,
  }
  const next = [session, ...sessions.filter((item) => item.fingerprint !== fingerprint)]
    .sort((left, right) => right.updatedAt - left.updatedAt)
    .slice(0, MAX_SAVED_SESSIONS)
  storage.setItem(UPLOAD_SESSIONS_KEY, JSON.stringify(next))
  return { session, resumed: false }
}

export function clearUploadSession(fingerprint: string, storage: Storage) {
  const next = readSessions(storage).filter((session) => session.fingerprint !== fingerprint)
  storage.setItem(UPLOAD_SESSIONS_KEY, JSON.stringify(next))
}

export function normalizeRecentJobs(payload: unknown): DistributionJobSummary[] {
  if (!Array.isArray(payload)) return []
  return payload.flatMap((raw) => {
    if (!raw || typeof raw !== 'object') return []
    const row = raw as Record<string, unknown>
    const jobId = numberValue(row.jobId, -1)
    if (jobId < 0) return []
    return [{
      jobId,
      uploadId: stringValue(row.uploadId),
      originalFilename: stringValue(row.originalFilename, '-'),
      status: stringValue(row.status, 'UNKNOWN').toUpperCase(),
      artifactId: row.artifactId == null ? null : numberValue(row.artifactId),
      targetVersion: nullableString(row.targetVersion),
      sourceMode: nullableString(row.sourceMode),
      rampUpFactor: row.rampUpFactor == null ? null : numberValue(row.rampUpFactor),
      updatePackageType: nullableString(row.updatePackageType),
      createdAt: nullableString(row.createdAt),
      completedAt: nullableString(row.completedAt),
      cancelledAt: nullableString(row.cancelledAt),
      errorMessage: nullableString(row.errorMessage),
    }]
  })
}

export function normalizeJobStatus(payload: unknown): DistributionJobStatus | null {
  if (!payload || typeof payload !== 'object') return null
  const row = payload as Record<string, unknown>
  const jobId = numberValue(row.jobId, -1)
  if (jobId < 0) return null
  return {
    jobId,
    uploadId: stringValue(row.uploadId),
    status: stringValue(row.status, 'UNKNOWN').toUpperCase(),
    artifactId: row.artifactId == null ? null : numberValue(row.artifactId),
    targetVersion: nullableString(row.targetVersion),
    sourceMode: nullableString(row.sourceMode),
    rampUpFactor: row.rampUpFactor == null ? null : numberValue(row.rampUpFactor),
    updatePackageType: nullableString(row.updatePackageType),
    totalAgents: Math.max(0, numberValue(row.totalAgents)),
    completed: Math.max(0, numberValue(row.completed)),
    failed: Math.max(0, numberValue(row.failed)),
    inProgress: Math.max(0, numberValue(row.inProgress)),
    cancelled: Math.max(0, numberValue(row.cancelled)),
    progress: Math.min(1, Math.max(0, numberValue(row.progress))),
    createdAt: nullableString(row.createdAt),
    completedAt: nullableString(row.completedAt),
    cancelledAt: nullableString(row.cancelledAt),
    waves: row.waves && typeof row.waves === 'object'
      ? row.waves as DistributionJobStatus['waves']
      : undefined,
  }
}

export function normalizeWaves(status: DistributionJobStatus | null): DistributionWaveStatus[] {
  const items = status?.waves?.items
  if (!items || typeof items !== 'object') return []
  return Object.entries(items).map(([key, raw]) => ({
    wave: numberValue(key),
    total: Math.max(0, numberValue(raw.total)),
    queued: Math.max(0, numberValue(raw.queued)),
    inProgress: Math.max(0, numberValue(raw.inProgress)),
    completed: Math.max(0, numberValue(raw.completed)),
    failed: Math.max(0, numberValue(raw.failed)),
    cancelled: Math.max(0, numberValue(raw.cancelled)),
  })).sort((left, right) => left.wave - right.wave)
}

export function normalizeEvents(payload: unknown): DistributionJobEvent[] {
  if (!Array.isArray(payload)) return []
  return payload.flatMap((raw, index) => {
    if (!raw || typeof raw !== 'object') return []
    const row = raw as Record<string, unknown>
    return [{
      id: numberValue(row.id, index),
      eventType: stringValue(row.eventType, 'UNKNOWN'),
      detailJson: nullableString(row.detailJson),
      createdAt: nullableString(row.createdAt),
    }]
  })
}

export function jobStatusLabel(status?: string | null) {
  switch (status?.toUpperCase()) {
    case 'QUEUED': return '대기'
    case 'IN_PROGRESS': return '진행 중'
    case 'COMPLETED': return '완료'
    case 'FAILED': return '실패'
    case 'CANCELLED': return '취소'
    default: return status || '-'
  }
}

export function jobStatusTone(status?: string | null) {
  switch (status?.toUpperCase()) {
    case 'COMPLETED': return 'online'
    case 'QUEUED':
    case 'IN_PROGRESS': return 'running'
    case 'FAILED': return 'warning'
    default: return 'offline'
  }
}

export function canCancelJob(status?: string | null) {
  return status === 'QUEUED' || status === 'IN_PROGRESS'
}

export function canRetryJob(status: DistributionJobStatus | null) {
  return Boolean(status && status.status !== 'CANCELLED' && status.failed > 0)
}

export function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  const value = bytes / 1024 ** index
  return `${value.toLocaleString('ko-KR', { maximumFractionDigits: index === 0 ? 0 : 1 })} ${units[index]}`
}

export function formatDateTime(value?: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value.replace('T', ' ') : date.toLocaleString('ko-KR')
}

export function formatEventDetail(detail?: string | null) {
  if (!detail) return '-'
  try {
    const parsed = JSON.parse(detail) as unknown
    if (!parsed || typeof parsed !== 'object') return String(parsed)
    return Object.entries(parsed as Record<string, unknown>)
      .map(([key, value]) => `${key}: ${typeof value === 'object' ? JSON.stringify(value) : String(value)}`)
      .join(' · ')
  } catch {
    return detail
  }
}
