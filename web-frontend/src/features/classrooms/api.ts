import { api, extractList } from '../../api/client'
import type { ClassroomSummary, CommandResult, PcStatus } from '../../types/dashboard'

type PageResponse<T> = { content?: T[]; data?: T[] }

export async function getClassrooms() {
  const payload = await api.get<ClassroomSummary[] | PageResponse<ClassroomSummary>>(
    '/dashboard/classrooms?page=0&size=200&sort=groupName,asc',
  )
  return extractList(payload)
}

export interface ClassroomDashboardSnapshot {
  rooms: ClassroomSummary[]
  pcs: PcStatus[]
  fetchedAt: string
}

export async function getClassroomDashboard(): Promise<ClassroomDashboardSnapshot> {
  const [rooms, pcs] = await Promise.all([
    getClassrooms(),
    getAllPcs(),
  ])
  return {
    rooms,
    pcs,
    fetchedAt: new Date().toISOString(),
  }
}

export function getAllPcs() {
  return api.get<PcStatus[]>('/dashboard/pcs')
}

export function sendRoomCommand(groupId: number, type: 'WOL' | 'SHUTDOWN') {
  return api.post<CommandResult>('/commands', {
    scope: 'GROUP',
    groupId,
    role: 'ALL',
    type,
  })
}
