import { describe, expect, it } from 'vitest'
import { ALL_FEATURE_PERMISSIONS, DEFAULT_FEATURE_PERMISSIONS, FEATURE_PERMISSION_GROUPS, normalizePermissions } from './permissions'

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

  it('explains that the broken-PC edit permission also controls device notes', () => {
    const group = FEATURE_PERMISSION_GROUPS.find((candidate) => candidate.key === 'broken')
    const option = group?.options.find((candidate) => candidate.value === 'BROKEN_PC_REGISTER_EDIT')

    expect(group?.label).toContain('장치 메모')
    expect(option?.label).toContain('장치 메모')
    expect(option?.description).toContain('사용 가능한 PC')
  })
})
