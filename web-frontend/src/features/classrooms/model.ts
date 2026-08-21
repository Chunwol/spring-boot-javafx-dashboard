import type { ClassroomSummary, PcStatus, RoomStatus } from '../../types/dashboard'

export interface RoomCounts {
  total: number
  online: number
  broken: number
  offline: number
}

export function isOnlinePc(pc: PcStatus) {
  const state = pc.status?.trim().toUpperCase()
  return pc.online || state === 'ONLINE' || state === 'IDLE'
}

export function isBrokenPc(pc: PcStatus) {
  return pc.status?.toUpperCase() === 'BROKEN'
    || pc.macAddress?.toUpperCase().startsWith('BROKEN-')
    || pc.pcName?.startsWith('미사용 PC')
}

export function seatNumber(pc: PcStatus) {
  const nameMatch = pc.pcName?.match(/(\d+)\s*$/)
  if (nameMatch) return Number(nameMatch[1])
  const noteMatch = pc.brokenNote?.match(/(\d+)\s*번/)
  return noteMatch ? Number(noteMatch[1]) : Number.MAX_SAFE_INTEGER
}

export function seatLabel(pc: PcStatus) {
  const seat = seatNumber(pc)
  return seat === Number.MAX_SAFE_INTEGER ? '?' : String(seat)
}

export function sortPcs(pcs: PcStatus[]) {
  return [...pcs].sort((a, b) => {
    const roleOrder = Number(b.role?.toUpperCase() === 'TEACHER') - Number(a.role?.toUpperCase() === 'TEACHER')
    return roleOrder || seatNumber(a) - seatNumber(b) || a.pcName.localeCompare(b.pcName, 'ko')
  })
}

export function getRoomCounts(room: ClassroomSummary, pcs: PcStatus[]): RoomCounts {
  const broken = pcs.filter(isBrokenPc).length
  const total = Math.max(0, room.totalPcs, pcs.length)
  const online = Math.min(total - broken, pcs.filter((pc) => !isBrokenPc(pc) && isOnlinePc(pc)).length)
  return { total, online, broken, offline: Math.max(0, total - online - broken) }
}

export function roomStatus(room: ClassroomSummary, counts?: Pick<RoomCounts, 'total' | 'online'>): RoomStatus {
  if (room.runningCommandCount > 0) return 'running'
  if ((counts?.total ?? room.totalPcs) <= 0 || (counts?.online ?? room.onlinePcs) <= 0) return 'offline'
  const teacherOnline = ['ONLINE', 'IDLE'].includes(room.teacherPcStatus?.toUpperCase() || '')
  return teacherOnline ? 'online' : 'warning'
}

export const roomStatusLabel: Record<RoomStatus, string> = {
  online: '온라인',
  running: '명령 수행 중',
  warning: '경고',
  offline: '오프라인',
}
