export type UserRole = 'ADMIN' | 'USER' | 'VIEWER'

export interface AuthUser {
  username: string
  role: UserRole
}

export interface LoginResponse extends AuthUser {
  token: string
}

export interface StoredSession extends AuthUser {
  token: string
}
