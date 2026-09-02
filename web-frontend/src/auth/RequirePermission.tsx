import type { PropsWithChildren } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from './AuthProvider'
import type { FeaturePermission } from './permissions'

export function RequirePermission({ permission, children }: PropsWithChildren<{ permission: FeaturePermission }>) {
  const { hasPermission } = useAuth()
  if (!hasPermission(permission)) return <Navigate to="/forbidden" replace />
  return children
}
