import { describe, expect, it } from 'vitest'
import { ALL_FEATURE_PERMISSIONS, DEFAULT_FEATURE_PERMISSIONS, normalizePermissions } from './permissions'

describe('feature permissions', () => {
  it('always gives administrators every feature', () => {
    expect(normalizePermissions('ADMIN', [])).toEqual(ALL_FEATURE_PERMISSIONS)
  })

  it('preserves the legacy operator defaults when no selection was stored', () => {
    expect(normalizePermissions('USER', undefined)).toEqual(DEFAULT_FEATURE_PERMISSIONS)
  })

  it('expands legacy modules and keeps detailed selections exact', () => {
    expect(normalizePermissions('USER', ['FILES', 'COMMANDS', 'UNKNOWN']))
      .toContain('FILE_UPLOAD_DISTRIBUTE')
    expect(normalizePermissions('USER', ['POWER_ON', 'UNKNOWN'])).toEqual(['POWER_ON'])
    expect(normalizePermissions('USER', [])).toEqual([])
  })
})
