import { api } from '../../api/client'

export interface OperationalAlert {
  id: number
  sourceMac?: string | null
  message: string
  severity: 'INFO' | 'WARN' | 'CRITICAL' | string
  alertType: string
  acknowledged: boolean
  acknowledgedBy?: string | null
  acknowledgedAt?: string | null
  createdAt: string
}

export const alertsApi = {
  list: () => api.get<OperationalAlert[]>('/alerts?limit=200&unreadOnly=false'),
  acknowledge: (id: number) => api.post<OperationalAlert>(`/alerts/${id}/ack`),
  acknowledgeSelected: (alertIds: number[]) => api.post<{ acknowledged: number }>('/alerts/ack-selected', { alertIds }),
}
