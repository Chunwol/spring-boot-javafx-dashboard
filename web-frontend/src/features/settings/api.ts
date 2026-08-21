import { api } from '../../api/client'

export const settingsApi = {
  get: (key: string) => api.get<{ key: string; value: string }>(`/settings/${encodeURIComponent(key)}`),
  put: (key: string, value: string) => api.put<void>(`/settings/${encodeURIComponent(key)}`, { value }),
  changePassword: (currentPassword: string, newPassword: string) => api.put<{ status: string }>('/admin/change-password', { currentPassword, newPassword }),
}
