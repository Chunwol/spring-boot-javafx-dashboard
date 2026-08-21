import { strFromU8, unzipSync } from 'fflate'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PcStatus } from '../../types/dashboard'
import { createTransferArtifact, getOperationStatuses, joinRemotePath, parseRemoteListing, sendPcOperation } from './operations'

vi.mock('../../api/client', () => ({
  api: { post: vi.fn(), get: vi.fn() },
}))

const mockedPost = vi.mocked(api.post)

beforeEach(() => {
  vi.clearAllMocks()
})

function readBlob(blob: Blob) {
  return new Promise<ArrayBuffer>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as ArrayBuffer)
    reader.onerror = () => reject(reader.error)
    reader.readAsArrayBuffer(blob)
  })
}

describe('PC operations helpers', () => {
  it('queues a large explicit selection with one MULTI request', async () => {
    const pcs = Array.from({ length: 850 }, (_, index) => ({
      macAddress: `00:00:00:00:${String(Math.floor(index / 100)).padStart(2, '0')}:${String(index % 100).padStart(2, '0')}`,
      pcName: `PC-${index + 1}`,
      online: true,
    })) satisfies PcStatus[]
    mockedPost.mockResolvedValue({ queued: 847, duplicateSkipped: 1, offlineSkipped: 1, notFoundSkipped: 1, requested: 850, taskIds: [1, 2] })

    const result = await sendPcOperation(pcs, { type: 'COLLECT_FILES', payload: { paths: ['C:\\logs'] } })

    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(mockedPost).toHaveBeenCalledWith('/commands', expect.objectContaining({ scope: 'MULTI', targetMacs: pcs.map((pc) => pc.macAddress) }))
    expect(result).toEqual({ requested: 850, queued: 847, duplicateSkipped: 1, offlineSkipped: 1, notFoundSkipped: 1, failed: 0, taskIds: [1, 2] })
  })

  it('does not send an empty target request', async () => {
    await expect(sendPcOperation([], { type: 'RUN_PS1' })).resolves.toMatchObject({ requested: 0, queued: 0, taskIds: [] })
    expect(mockedPost).not.toHaveBeenCalled()
  })

  it('loads collection progress with one batch status request', async () => {
    mockedPost.mockResolvedValue([{ taskId: 3, status: 'COMPLETED', macAddress: 'AA:BB:CC:DD:EE:FF', commandType: 'COLLECT_FILES' }])

    await expect(getOperationStatuses([3, 3])).resolves.toHaveLength(1)

    expect(mockedPost).toHaveBeenCalledWith('/commands/status', { taskIds: [3] })
  })

  it('joins Windows and Unix remote paths without duplicate separators', () => {
    expect(joinRemotePath('C:\\Users\\student\\', '\\Desktop')).toBe('C:\\Users\\student\\Desktop')
    expect(joinRemotePath('/home/student/', '/Downloads')).toBe('/home/student/Downloads')
    expect(joinRemotePath('', 'C:\\')).toBe('C:\\')
  })

  it('parses and normalizes a remote listing', () => {
    expect(parseRemoteListing(JSON.stringify({
      ok: true,
      currentPath: 'C:\\',
      parentPath: '',
      entries: [{ name: 'Users', type: 'dir', size: 0 }, { name: 'readme.txt', type: 'file', size: 12 }],
      hasMore: false,
      nextOffset: 2,
      partial: true,
      warningsPreview: ['one entry skipped'],
    }))).toEqual({
      currentPath: 'C:\\',
      parentPath: '',
      entries: [
        { name: 'Users', type: 'dir', size: 0, displayName: undefined, usb: false, freeBytes: undefined, totalBytes: undefined },
        { name: 'readme.txt', type: 'file', size: 12, displayName: undefined, usb: false, freeBytes: undefined, totalBytes: undefined },
      ],
      hasMore: false,
      nextOffset: 2,
      partial: true,
      warnings: ['one entry skipped'],
    })
  })

  it('rejects malformed listing output', () => {
    expect(() => parseRemoteListing('not-json')).toThrow('올바른 파일 목록')
    expect(() => parseRemoteListing('{"ok":false,"error":"denied"}')).toThrow('denied')
  })

  it('keeps a single ZIP as a normal file instead of requesting extraction', async () => {
    const file = new File(['zip-bytes'], 'archive.zip', { type: 'application/zip' })
    const result = await createTransferArtifact([file])
    expect(result.file).toBe(file)
    expect(result).toMatchObject({ zipped: false, sourceType: 'file' })
  })

  it('rejects an oversized folder before loading files into browser memory', async () => {
    const firstRead = vi.fn()
    const secondRead = vi.fn()
    const files = [
      { name: 'large.bin', size: 128 * 1024 * 1024, webkitRelativePath: 'folder/large.bin', arrayBuffer: firstRead },
      { name: 'extra.bin', size: 1, webkitRelativePath: 'folder/extra.bin', arrayBuffer: secondRead },
    ] as unknown as File[]

    await expect(createTransferArtifact(files)).rejects.toThrow('128MB까지만 자동 ZIP')
    expect(firstRead).not.toHaveBeenCalled()
    expect(secondRead).not.toHaveBeenCalled()
  })

  it('creates a valid ZIP for a small folder while preserving relative paths', async () => {
    const encoder = new TextEncoder()
    const firstBytes = encoder.encode('alpha')
    const secondBytes = encoder.encode('beta')
    const first = {
      name: 'first.txt', size: firstBytes.byteLength, webkitRelativePath: 'sample/first.txt',
      arrayBuffer: async () => firstBytes.buffer,
    } as File
    const second = {
      name: 'second.txt', size: secondBytes.byteLength, webkitRelativePath: 'sample/nested/second.txt',
      arrayBuffer: async () => secondBytes.buffer,
    } as File

    const result = await createTransferArtifact([first, second])
    const contents = unzipSync(new Uint8Array(await readBlob(result.file)))

    expect(result).toMatchObject({ zipped: true, sourceType: 'folder' })
    expect(result.file.name).toBe('sample.zip')
    expect(strFromU8(contents['sample/first.txt'])).toBe('alpha')
    expect(strFromU8(contents['sample/nested/second.txt'])).toBe('beta')
  })
})
