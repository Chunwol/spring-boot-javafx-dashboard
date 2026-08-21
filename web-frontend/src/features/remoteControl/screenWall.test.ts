import { describe, expect, it } from 'vitest'
import type { PcStatus } from '../../types/dashboard'
import {
  canStartScreenWallPc,
  nextScreenWallPollDelay,
  nextScreenWallStartCount,
  screenWallPcs,
  SCREEN_WALL_STATUS_POLL_MAX_MS,
  SCREEN_WALL_MAX_PCS,
} from './screenWall'

function pc(index: number, overrides: Partial<PcStatus> = {}): PcStatus {
  return {
    macAddress: `02:00:00:00:00:${index.toString(16).padStart(2, '0')}`,
    pcName: `214-${index}`,
    online: true,
    role: 'STUDENT',
    ...overrides,
  }
}

describe('screenWall', () => {
  it('selects up to fifty student PCs in seat order', () => {
    const pcs = [pc(0, { pcName: '214_T', role: 'TEACHER' })]
    for (let index = 55; index >= 1; index--) pcs.push(pc(index))

    const selected = screenWallPcs(pcs)

    expect(selected).toHaveLength(SCREEN_WALL_MAX_PCS)
    expect(selected[0].pcName).toBe('214-1')
    expect(selected[49].pcName).toBe('214-50')
    expect(selected.every((item) => item.role === 'STUDENT')).toBe(true)
  })

  it('starts only healthy online PCs with a MAC address', () => {
    expect(canStartScreenWallPc(pc(1))).toBe(true)
    expect(canStartScreenWallPc(pc(2, { online: false, status: 'OFFLINE' }))).toBe(false)
    expect(canStartScreenWallPc(pc(3, { status: 'BROKEN' }))).toBe(false)
    expect(canStartScreenWallPc(pc(4, { macAddress: '' }))).toBe(false)
  })

  it('advances connection starts four at a time without exceeding the room size', () => {
    expect(nextScreenWallStartCount(0, 50)).toBe(4)
    expect(nextScreenWallStartCount(48, 50)).toBe(50)
    expect(nextScreenWallStartCount(50, 50)).toBe(50)
    expect(nextScreenWallStartCount(4, 6)).toBe(6)
  })

  it('backs off pending-session polling without exceeding the cap', () => {
    expect(nextScreenWallPollDelay(1_000)).toBe(1_500)
    expect(nextScreenWallPollDelay(1_500)).toBe(2_250)
    expect(nextScreenWallPollDelay(2_250)).toBe(SCREEN_WALL_STATUS_POLL_MAX_MS)
    expect(nextScreenWallPollDelay(SCREEN_WALL_STATUS_POLL_MAX_MS)).toBe(SCREEN_WALL_STATUS_POLL_MAX_MS)
  })
})
