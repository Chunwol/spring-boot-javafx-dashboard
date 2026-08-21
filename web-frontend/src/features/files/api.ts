import { ApiError, api, getApiBaseUrl } from '../../api/client'
import { FILE_CHUNK_SIZE } from './model'
import type {
  AssembleResult,
  ChunkUploadResult,
  CreateDistributionJobInput,
  CreatedDistributionJob,
  DistributionJobEvent,
  DistributionJobStatus,
  DistributionJobSummary,
  FileUploadProgress,
  UploadedArtifact,
} from './types'

const CHUNK_TIMEOUT_MS = 5 * 60_000
const ASSEMBLE_TIMEOUT_MS = 2 * 60 * 60_000

function errorMessage(body: unknown, fallback: string) {
  if (!body || typeof body !== 'object') return fallback
  const row = body as Record<string, unknown>
  for (const key of ['message', 'detail', 'error', 'code']) {
    if (typeof row[key] === 'string' && row[key]) return row[key]
  }
  return fallback
}

async function authorizedRequest<T>(path: string, token: string, init: RequestInit, timeoutMs: number) {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs)
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${token}`)

  try {
    const response = await fetch(`${getApiBaseUrl()}${path}`, { ...init, headers, signal: controller.signal })
    const contentType = response.headers.get('content-type') || ''
    const body: unknown = contentType.includes('application/json') ? await response.json() : await response.text()
    if (response.status === 401) window.dispatchEvent(new CustomEvent('auth:unauthorized'))
    if (!response.ok) {
      throw new ApiError(errorMessage(body, `파일 요청을 처리하지 못했습니다. (${response.status})`), response.status, body)
    }
    return body as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiError('파일 처리 시간이 초과되었습니다. 같은 파일을 선택해 이어서 시도하세요.', 0)
    }
    throw new ApiError('파일 전송 중 서버 연결이 끊어졌습니다. 같은 파일을 선택해 이어서 시도하세요.', 0, error)
  } finally {
    window.clearTimeout(timeout)
  }
}

function readBlob(blob: Blob): Promise<ArrayBuffer> {
  if (typeof blob.arrayBuffer === 'function') return blob.arrayBuffer()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(reader.error || new Error('파일 청크를 읽지 못했습니다.'))
    reader.onload = () => resolve(reader.result as ArrayBuffer)
    reader.readAsArrayBuffer(blob)
  })
}

export async function sha256Blob(blob: Blob) {
  const digest = await crypto.subtle.digest('SHA-256', new Uint8Array(await readBlob(blob)))
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

export async function uploadAndAssembleFile({
  file,
  token,
  uploadId,
  chunkSize = FILE_CHUNK_SIZE,
  onProgress,
}: {
  file: File
  token: string
  uploadId: string
  chunkSize?: number
  onProgress?: (progress: FileUploadProgress) => void
}): Promise<UploadedArtifact> {
  const totalChunks = Math.ceil(file.size / chunkSize)
  if (!file.size || !totalChunks) throw new Error('빈 파일은 업로드할 수 없습니다.')
  let resumedChunks = 0

  for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex += 1) {
    const start = chunkIndex * chunkSize
    const end = Math.min(file.size, start + chunkSize)
    const chunk = file.slice(start, end)
    onProgress?.({ phase: 'hashing', chunkIndex, totalChunks, uploadedBytes: start, totalBytes: file.size, resumedChunks })
    const chunkHash = await sha256Blob(chunk)
    const formData = new FormData()
    formData.append('file', chunk, `${file.name}.part-${chunkIndex}`)
    const query = new URLSearchParams({
      uploadId,
      chunkIndex: String(chunkIndex),
      totalChunks: String(totalChunks),
      chunkHash,
    })
    const result = await authorizedRequest<ChunkUploadResult>(
      `/file-chunks?${query}`,
      token,
      { method: 'POST', body: formData },
      CHUNK_TIMEOUT_MS,
    )
    if (result.resumed) resumedChunks += 1
    onProgress?.({ phase: 'uploading', chunkIndex, totalChunks, uploadedBytes: end, totalBytes: file.size, resumedChunks })
  }

  onProgress?.({ phase: 'assembling', chunkIndex: totalChunks, totalChunks, uploadedBytes: file.size, totalBytes: file.size, resumedChunks })
  const assembleQuery = new URLSearchParams({
    uploadId,
    originalFilename: file.name,
    totalChunks: String(totalChunks),
  })
  const assembled = await authorizedRequest<AssembleResult>(
    `/file-chunks/assemble?${assembleQuery}`,
    token,
    { method: 'POST' },
    ASSEMBLE_TIMEOUT_MS,
  )
  onProgress?.({ phase: 'complete', chunkIndex: totalChunks, totalChunks, uploadedBytes: file.size, totalBytes: file.size, resumedChunks })
  return { ...assembled, uploadId, originalFilename: file.name, totalChunks, resumedChunks }
}

export function createDistributionJob(input: CreateDistributionJobInput) {
  return api.post<CreatedDistributionJob>('/file-distribution/jobs', input)
}

export function getRecentDistributionJobs() {
  return api.get<DistributionJobSummary[]>('/file-distribution/jobs')
}

export function getDistributionJob(jobId: number) {
  return api.get<DistributionJobStatus>(`/file-distribution/jobs/${jobId}`)
}

export function getDistributionJobEvents(jobId: number) {
  return api.get<DistributionJobEvent[]>(`/file-distribution/jobs/${jobId}/events`)
}

export function cancelDistributionJob(jobId: number) {
  return api.post<{ jobId: number; status: string }>(`/file-distribution/jobs/${jobId}/cancel`)
}

export function retryFailedDistribution(jobId: number) {
  return api.post<{ jobId: number; resetCount?: number; waves?: number[] }>(`/file-distribution/jobs/${jobId}/retry-failed`, {})
}
