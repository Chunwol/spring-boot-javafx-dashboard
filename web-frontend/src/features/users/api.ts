import { api } from '../../api/client'
import type { FeaturePermission } from '../../auth/permissions'

export type UserRole = 'ADMIN' | 'USER' | 'VIEWER'

export interface ManagedUser {
  id: number
  username: string
  email: string
  role: UserRole
  note?: string | null
  permissions: FeaturePermission[]
  active: boolean
  failedLoginAttempts: number
  loggedIn: boolean
  loginIpAddress?: string | null
  loginUserAgent?: string | null
  loginAt?: string | null
  lastActivityAt?: string | null
}

export interface CreateUserInput {
  username: string
  password: string
  email: string
  role: UserRole
  note?: string
  permissions?: FeaturePermission[]
}

export interface UpdateUserInput {
  username: string
  email: string
  role: UserRole
  note?: string
  permissions?: FeaturePermission[]
}

export const usersApi = {
  list: () => api.get<ManagedUser[]>('/users'),
  create: (input: CreateUserInput) => api.post<ManagedUser>('/users', input),
  update: (id: number, input: UpdateUserInput) => api.put<ManagedUser>(`/users/${id}`, input),
  delete: (id: number) => api.delete<void>(`/users/${id}`),
}
