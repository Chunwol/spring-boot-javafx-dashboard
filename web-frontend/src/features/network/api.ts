import { api, extractList } from '../../api/client'
import type { ClassroomSummary } from '../../types/dashboard'
import type {
  ClassroomPage,
  ClassroomTrafficSummary,
  NetworkTraffic,
  PcTraffic,
  TrafficThresholds,
} from './types'

export function getNetworkTraffic() {
  return api.get<NetworkTraffic>('/dashboard/traffic')
}

export function getTrafficThresholds() {
  return api.get<TrafficThresholds>('/dashboard/config/traffic-thresholds')
}

export async function getNetworkClassrooms() {
  const payload = await api.get<ClassroomSummary[] | ClassroomPage>(
    '/dashboard/classrooms?page=0&size=200&sort=groupName,asc',
  )
  return extractList(payload)
}

export function getClassroomPcTraffic(classroomId: number) {
  return api.get<PcTraffic[]>(`/dashboard/classrooms/${classroomId}/traffic/pcs`)
}

export function getClassroomTrafficSummaries() {
  return api.get<ClassroomTrafficSummary[]>('/dashboard/classrooms/traffic/summary')
}
