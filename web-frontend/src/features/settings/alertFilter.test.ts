import { describe, expect, it } from 'vitest'
import { alertFilterSelected, parseHiddenAlertTypes, toggleAlertFilter } from './alertFilter'

describe('alert filters', () => {
  it('uses low-noise defaults for an empty setting', () => {
    expect([...parseHiddenAlertTypes('')]).toEqual(['PC_OFFLINE', 'TRAFFIC_HIGH'])
  })

  it('parses stored JSON arrays', () => {
    expect(parseHiddenAlertTypes('["SCMATE"]')).toEqual(new Set(['SCMATE']))
  })

  it('toggles every performance metric as one setting', () => {
    const hidden = toggleAlertFilter(new Set(), 'METRICS_PERF', false)
    expect(alertFilterSelected('METRICS_PERF', hidden)).toBe(false)
    expect(toggleAlertFilter(hidden, 'METRICS_PERF', true).size).toBe(0)
  })

  it('toggles classroom on and off notifications together', () => {
    const hidden = toggleAlertFilter(new Set(), 'CLASSROOM_POWER', false)
    expect(hidden).toEqual(new Set(['CLASSROOM_POWER_ON', 'CLASSROOM_POWER_OFF']))
    expect(alertFilterSelected('CLASSROOM_POWER', hidden)).toBe(false)
  })
})
