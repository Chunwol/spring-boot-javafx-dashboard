import { api, extractList } from '../../api/client'
import type { CommandResult, PcStatus } from '../../types/dashboard'

export interface ClassroomGroup {
  id: number
  name: string
  description?: string | null
  createdAt?: string | null
}

export interface IpRange {
  id: number
  groupId: number
  groupName?: string | null
  cidr?: string | null
  startIp?: string | null
  endIp?: string | null
  description?: string | null
}

export interface TeacherDevice {
  id: number
  groupId: number
  groupName?: string | null
  ipAddress?: string | null
  macAddress?: string | null
  description?: string | null
}

export const classroomSettingsApi = {
  groups: async () => extractList(await api.get<ClassroomGroup[] | { content?: ClassroomGroup[] }>('/groups?page=0&size=200')),
  createGroup: (input: Pick<ClassroomGroup, 'name' | 'description'>) => api.post<ClassroomGroup>('/groups', input),
  updateGroup: (id: number, input: Pick<ClassroomGroup, 'name' | 'description'>) => api.put<ClassroomGroup>(`/groups/${id}`, input),
  deleteGroup: (id: number) => api.delete<void>(`/groups/${id}`),
  ipRanges: (groupId: number) => api.get<IpRange[]>(`/dashboard/classrooms/${groupId}/ip-ranges`),
  createIpRange: (groupId: number, input: Partial<IpRange>) => api.post<IpRange>(`/dashboard/classrooms/${groupId}/ip-ranges`, input),
  deleteIpRange: (id: number) => api.delete<void>(`/dashboard/classrooms/ip-ranges/${id}`),
  teacherDevices: (groupId: number) => api.get<TeacherDevice[]>(`/dashboard/classrooms/${groupId}/teacher-devices`),
  createTeacherDevice: (groupId: number, input: Partial<TeacherDevice>) => api.post<TeacherDevice>(`/dashboard/classrooms/${groupId}/teacher-devices`, input),
  deleteTeacherDevice: (id: number) => api.delete<void>(`/dashboard/classrooms/teacher-devices/${id}`),
  classroomPcs: async (groupId: number) => extractList(await api.get<PcStatus[] | { content?: PcStatus[] }>(`/dashboard/classrooms/${groupId}/pcs?page=0&size=1000`)),
  commandToIp: (ipAddress: string, type: 'SHUTDOWN' | 'REBOOT' | 'WOL') => api.post<CommandResult>('/commands/to-ip', { ipAddress, type }),
}
