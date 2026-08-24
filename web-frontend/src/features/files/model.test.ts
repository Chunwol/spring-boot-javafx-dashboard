import { describe, expect, it } from 'vitest'
import {
  canCancelJob,
  canRetryJob,
  clearUploadSession,
  fileFingerprint,
  formatBytes,
  formatEventDetail,
  normalizeJobStatus,
  normalizeRecentJobs,
  normalizeWaves,
  resolveUploadSession,
  totalChunksFor,
} from './model'

describe('file distribution model', () => {
  it('calculates chunks and human-readable sizes', () => {
    expect(totalChunksFor(0)).toBe(0)
    expect(totalChunksFor(8 * 1024 * 1024)).toBe(1)
    expect(totalChunksFor(8 * 1024 * 1024 + 1)).toBe(2)
    expect(formatBytes(12 * 1024 * 1024)).toBe('12 MB')
  })

  it('reuses an upload session only for the exact same file fingerprint', () => {
    localStorage.clear()
    const file = new File(['agent'], 'agent.jar', { lastModified: 123 })
    const first = resolveUploadSession(file, localStorage, 100, () => 'web-upload-12345678')
    const second = resolveUploadSession(file, localStorage, 200, () => 'unused-upload-id')
    expect(first.resumed).toBe(false)
    expect(second.resumed).toBe(true)
    expect(second.session.uploadId).toBe(first.session.uploadId)

    clearUploadSession(fileFingerprint(file), localStorage)
    const third = resolveUploadSession(file, localStorage, 300, () => 'web-upload-87654321')
    expect(third.resumed).toBe(false)
    expect(third.session.uploadId).toBe('web-upload-87654321')
  })

  it('normalizes malformed recent rows and sorts wave records by number', () => {
    const jobs = normalizeRecentJobs([
      { jobId: '8', originalFilename: 'agent.jar', status: 'completed', rampUpFactor: '1.8' },
      { originalFilename: 'broken' },
    ])
    expect(jobs).toHaveLength(1)
    expect(jobs[0]).toMatchObject({ jobId: 8, status: 'COMPLETED', rampUpFactor: 1.8 })

    const status = normalizeJobStatus({
      jobId: '8', status: 'IN_PROGRESS', totalAgents: '12', failed: 2, progress: 1.5,
      waves: { items: { 2: { total: 4, queued: 1 }, 0: { total: 3, completed: 3 } } },
    })
    expect(status?.progress).toBe(1)
    expect(normalizeWaves(status).map((wave) => wave.wave)).toEqual([0, 2])
    expect(normalizeWaves(status)[0]).toMatchObject({ total: 3, completed: 3, failed: 0 })
  })

  it('normalizes PC direct-copy metadata and target verification progress', () => {
    const status = normalizeJobStatus({
      jobId: 91,
      status: 'in_progress',
      updatePackageType: 'PC_COPY',
      sourceAgentId: 'AA:BB:CC:DD:EE:01',
      sourcePath: 'C:\\Rocky10',
      targetPath: 'C:\\Rocky10',
      overwriteExisting: true,
      targets: [{ agentId: 'AA:BB:CC:DD:EE:02', status: 'completed', verifiedFiles: '38', verifiedBytes: '1024' }],
    })
    expect(status).toMatchObject({
      updatePackageType: 'PC_COPY',
      sourceAgentId: 'AA:BB:CC:DD:EE:01',
      sourcePath: 'C:\\Rocky10',
      overwriteExisting: true,
    })
    expect(status?.targets?.[0]).toMatchObject({ status: 'COMPLETED', verifiedFiles: 38, verifiedBytes: 1024 })
  })

  it('permits only meaningful cancel and retry actions', () => {
    expect(canCancelJob('QUEUED')).toBe(true)
    expect(canCancelJob('COMPLETED')).toBe(false)
    expect(canRetryJob(normalizeJobStatus({ jobId: 1, status: 'FAILED', failed: 2 }))).toBe(true)
    expect(canRetryJob(normalizeJobStatus({ jobId: 2, status: 'CANCELLED', failed: 2 }))).toBe(false)
  })

  it('renders JSON event details without failing on plain text', () => {
    expect(formatEventDetail('{"wave":2,"success":true}')).toBe('wave: 2 · success: true')
    expect(formatEventDetail('plain detail')).toBe('plain detail')
  })
})
