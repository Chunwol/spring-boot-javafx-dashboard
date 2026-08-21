import { api } from '../../api/client'

export interface RegistrationStatus { enabled: boolean }
export interface InstallKeyInfo { configured: boolean; keyPreview?: string; key?: string }

export interface AgentAdminView {
  macAddress: string
  pcName?: string | null
  ipAddress?: string | null
  status?: string | null
  role?: string | null
  groupId?: number | null
  groupName?: string | null
  lastSeen?: string | null
  tokenActive: boolean
  tokenIssuedAt?: string | null
  tokenLastUsedAt?: string | null
  orphan?: boolean
}

export const tokenApi = {
  getRegistrationStatus: () => api.get<RegistrationStatus>('/agent/registration-status'),
  setRegistrationStatus: (enabled: boolean) => api.put<void>('/agent/registration-status', { enabled }),
  getInstallKey: () => api.get<InstallKeyInfo>('/admin/install-key'),
  rotateInstallKey: () => api.put<InstallKeyInfo>('/admin/install-key'),
  getAgents: () => api.get<AgentAdminView[]>('/admin/agents'),
  revokeAgent: (mac: string) => api.put<{ status: string; mac: string }>(`/admin/agents/${encodeURIComponent(mac)}/revoke`),
  deleteAgent: (mac: string) => api.delete<{ status: string; mac: string }>(`/admin/agents/${encodeURIComponent(mac)}`),
  revokeAgents: (macAddresses: string[]) => api.put<{ status: string; count: number }>('/admin/agents/bulk/revoke', { macAddresses }),
  deleteAgents: (macAddresses: string[]) => api.post<{ status: string; requested: number; deleted: number }>('/admin/agents/bulk/delete', { macAddresses }),
  downloadConfig: () => api.download('/agent/config/download', 'config.json'),
}
