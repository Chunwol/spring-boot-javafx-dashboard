import type { PropsWithChildren } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from './AuthProvider'
import type { UserRole } from './types'

export function RequireRole({ roles, children }: PropsWithChildren<{ roles: UserRole[] }>) {
  const { user } = useAuth()
  if (!user || !roles.includes(user.role)) return <Navigate to="/forbidden" replace />
  return children
}
