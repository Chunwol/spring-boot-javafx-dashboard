import { describe, expect, it } from 'vitest'
import { claimChunkReload, dynamicImportErrorSignature } from './RouteErrorBoundary'

function memoryStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
  }
}

describe('RouteErrorBoundary chunk recovery', () => {
  it('recognizes Vite dynamic import failures by their module URL', () => {
    const error = new TypeError('Failed to fetch dynamically imported module: https://pelmon.kro.kr/assets/CommandsPage-D4L5ai8I.js')

    expect(dynamicImportErrorSignature(error)).toBe('https://pelmon.kro.kr/assets/CommandsPage-D4L5ai8I.js')
  })

  it('allows one automatic reload for the same missing module', () => {
    const storage = memoryStorage()
    const error = new TypeError('Failed to fetch dynamically imported module: https://pelmon.kro.kr/assets/CommandsPage-D4L5ai8I.js')

    expect(claimChunkReload(error, storage)).toBe(true)
    expect(claimChunkReload(error, storage)).toBe(false)
  })

  it('does not reload for unrelated route errors', () => {
    expect(claimChunkReload(new Error('render failed'), memoryStorage())).toBe(false)
  })
})
