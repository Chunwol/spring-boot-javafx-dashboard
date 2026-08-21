import { beforeEach, describe, expect, it, vi } from 'vitest'
import { sha256Blob, uploadAndAssembleFile } from './api'

describe('file distribution upload API', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('computes lowercase SHA-256 in the browser', async () => {
    await expect(sha256Blob(new Blob(['abc']))).resolves.toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
  })

  it('uploads chunks in order, reports resumed chunks, and assembles without a shared-secret header', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = String(input)
      calls.push({ url, init })
      if (url.includes('/assemble?')) {
        return new Response(JSON.stringify({
          id: 42,
          sha256: 'final',
          sizeBytes: 6,
          downloadUrl: '/api/v1/scripts/42/download',
          deduplicated: false,
          manifestKey: 'manifest',
        }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      const chunkIndex = Number(new URL(url, 'http://localhost').searchParams.get('chunkIndex'))
      return new Response(JSON.stringify({
        uploadId: 'web-upload-12345678',
        chunkIndex,
        totalChunks: 2,
        uploadedChunks: chunkIndex + 1,
        missingChunks: 1 - chunkIndex,
        missingChunkIndexesPreview: [],
        resumed: chunkIndex === 0,
        complete: chunkIndex === 1,
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    })
    const progress = vi.fn()

    const result = await uploadAndAssembleFile({
      file: new File(['abcdef'], 'agent.jar'),
      token: 'jwt-token',
      uploadId: 'web-upload-12345678',
      chunkSize: 3,
      onProgress: progress,
    })

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(calls[0].url).toContain('chunkIndex=0')
    expect(calls[1].url).toContain('chunkIndex=1')
    expect(calls[2].url).toContain('/file-chunks/assemble?')
    expect(new Headers(calls[0].init?.headers).get('Authorization')).toBe('Bearer jwt-token')
    expect(new Headers(calls[0].init?.headers).has('X-Signature')).toBe(false)
    expect(result).toMatchObject({ id: 42, resumedChunks: 1, totalChunks: 2 })
    expect(progress).toHaveBeenLastCalledWith(expect.objectContaining({ phase: 'complete', resumedChunks: 1 }))
  })
})
