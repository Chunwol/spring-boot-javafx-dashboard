import { beforeEach, describe, expect, it, vi } from 'vitest'
import { queueRemoteDesktopFiles, waitForRemoteFileTransfers } from './fileTransfer'

const mocks = vi.hoisted(() => ({
  upload: vi.fn(),
  resolve: vi.fn(),
  clear: vi.fn(),
  send: vi.fn(),
  statuses: vi.fn(),
}))

vi.mock('../files/api', () => ({ uploadAndAssembleFile: mocks.upload }))
vi.mock('../files/model', () => ({ resolveUploadSession: mocks.resolve, clearUploadSession: mocks.clear }))
vi.mock('../pcs/operations', () => ({ sendPcOperation: mocks.send, getOperationStatuses: mocks.statuses }))

const pc = { macAddress: 'AA:BB:CC:DD:EE:FF', pcName: '214_T', online: true }

describe('remote clipboard file transfer', () => {
  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset())
    mocks.resolve.mockReturnValue({ session: { uploadId: 'upload-1', fingerprint: 'fingerprint-1' } })
    mocks.upload.mockResolvedValue({ downloadUrl: '/api/v1/scripts/42/download' })
    mocks.send.mockResolvedValue({ requested: 1, queued: 1, failed: 0, duplicateSkipped: 0, offlineSkipped: 0, notFoundSkipped: 0, taskIds: [91] })
  })

  it('uploads a clipboard file and queues a rename-safe desktop transfer', async () => {
    const file = new File(['hello'], 'lecture.txt', { type: 'text/plain' })

    await expect(queueRemoteDesktopFiles({ files: [file], pc, token: 'jwt' })).resolves.toEqual([91])
    expect(mocks.upload).toHaveBeenCalledWith(expect.objectContaining({ file, token: 'jwt', uploadId: 'upload-1' }))
    expect(mocks.send).toHaveBeenCalledWith([pc], expect.objectContaining({
      type: 'RUN_PS1',
      scriptUrl: '/api/v1/scripts/42/download',
      payload: expect.objectContaining({ destinationMode: 'DESKTOP', conflictPolicy: 'RENAME', displayName: 'lecture.txt' }),
    }))
    expect(mocks.clear).toHaveBeenCalledWith('fingerprint-1', expect.anything())
  })

  it('waits until every file command reaches a terminal status', async () => {
    mocks.statuses
      .mockResolvedValueOnce([{ taskId: 91, status: 'ACKED' }])
      .mockResolvedValueOnce([{ taskId: 91, status: 'COMPLETED' }])

    await expect(waitForRemoteFileTransfers([91], { pollMs: 1, timeoutMs: 100 })).resolves.toEqual([
      { taskId: 91, status: 'COMPLETED' },
    ])
  })
})
