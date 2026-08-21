import { api } from '../../api/client'
import type { PcStatus } from '../../types/dashboard'

export interface BrokenPcInput {
  macAddress?: string
  ipAddress?: string
  groupId?: string
  seatNumber?: string
  note: string
}

export const brokenPcApi = {
  list: () => api.get<PcStatus[]>('/dashboard/pcs/broken'),
  create: (input: BrokenPcInput) => api.post<PcStatus>('/dashboard/pcs/broken', input),
  updateNote: (macAddress: string, note: string) => api.put<PcStatus>(`/dashboard/pcs/broken/${encodeURIComponent(macAddress)}`, { note }),
  clear: (macAddress: string) => api.delete<PcStatus>(`/dashboard/pcs/broken/${encodeURIComponent(macAddress)}`),
}
