import { api } from '../../api/client'

export type UserRole = 'ADMIN' | 'USER' | 'VIEWER'

export interface ManagedUser {
  id: number
  username: string
  email: string
  role: UserRole
  active: boolean
  failedLoginAttempts: number
}

export interface CreateUserInput {
  username: string
  password: string
  email: string
  role: UserRole
}

export interface UpdateUserInput {
  username: string
  email: string
  role: UserRole
}

export const usersApi = {
  list: () => api.get<ManagedUser[]>('/users'),
  create: (input: CreateUserInput) => api.post<ManagedUser>('/users', input),
  update: (id: number, input: UpdateUserInput) => api.put<ManagedUser>(`/users/${id}`, input),
  delete: (id: number) => api.delete<void>(`/users/${id}`),
}
