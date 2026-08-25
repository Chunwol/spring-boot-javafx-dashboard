import { isBrokenPc, isOnlinePc, sortPcs } from '../classrooms/model'
import type { PcStatus } from '../../types/dashboard'

export const SCREEN_WALL_MAX_PCS = 50
export const SCREEN_WALL_BATCH_SIZE = 4
export const SCREEN_WALL_BATCH_INTERVAL_MS = 1_000
export const SCREEN_WALL_STATUS_POLL_INITIAL_MS = 1_000
export const SCREEN_WALL_STATUS_POLL_MAX_MS = 3_000
export const SCREEN_WALL_RECONNECT_DELAY_MS = 2_000
export const SCREEN_WALL_RECONNECT_LIMIT = 3

export type ScreenWallScope = 'all' | 'teacher' | 'student'

export function screenWallPcs(pcs: PcStatus[], scope: ScreenWallScope = 'all') {
  const selected = pcs.filter((pc) => {
    const teacher = pc.role?.toUpperCase() === 'TEACHER'
    if (scope === 'teacher') return teacher
    if (scope === 'student') return !teacher
    return true
  })
  return sortPcs(selected)
    .slice(0, SCREEN_WALL_MAX_PCS)
}

export function canStartScreenWallPc(pc: PcStatus) {
  return isOnlinePc(pc) && !isBrokenPc(pc) && Boolean(pc.macAddress?.trim())
}

export function nextScreenWallStartCount(current: number, total: number) {
  return Math.min(total, current + SCREEN_WALL_BATCH_SIZE)
}

export function nextScreenWallPollDelay(current: number) {
  return Math.min(SCREEN_WALL_STATUS_POLL_MAX_MS, Math.ceil(current * 1.5))
}

export function canAutoReconnectScreenWallPc(pc: PcStatus, attempts: number) {
  return pc.role?.toUpperCase() !== 'TEACHER'
    && canStartScreenWallPc(pc)
    && attempts < SCREEN_WALL_RECONNECT_LIMIT
}
