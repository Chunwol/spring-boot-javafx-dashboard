import { describe, expect, it } from 'vitest'
import { EMPTY_SCRIPT_DRAFT, requiresExecutionAcknowledgement, scriptDraftError } from './model'

describe('script library model', () => {
  it('requires a PS1 file for a new template', () => {
    expect(scriptDraftError({ ...EMPTY_SCRIPT_DRAFT, name: '점검' }, false)).toContain('PS1')
  })

  it('requires acknowledgement for high risk or reboot scripts', () => {
    expect(requiresExecutionAcknowledgement('HIGH', false)).toBe(true)
    expect(requiresExecutionAcknowledgement('LOW', true)).toBe(true)
    expect(requiresExecutionAcknowledgement('LOW', false)).toBe(false)
  })
})
