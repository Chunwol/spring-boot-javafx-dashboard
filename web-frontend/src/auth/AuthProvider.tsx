import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { PropsWithChildren } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api, setApiAccessToken, setApiAccessTokenRefresher } from '../api/client'
import type { AuthUser, LoginResponse, StoredSession, UserRole } from './types'

const SESSION_KEY = 'classroom-dashboard.session'
const REMEMBERED_USERNAME_KEY = 'classroom-dashboard.username'

interface AuthContextValue {
  user: AuthUser | null
  token: string | null
  isAuthenticated: boolean
  isAdmin: boolean
  canWrite: boolean
  isRestoring: boolean
  rememberedUsername: string
  login: (username: string, password: string, rememberUsername: boolean, autoLogin: boolean) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

function normalizeRole(value: string | undefined): UserRole {
  const role = value?.trim().toUpperCase()
  if (role === 'ADMIN' || role === 'USER' || role === 'VIEWER') return role
  return 'VIEWER'
}

function tokenExpiresAt(token: string) {
  try {
    const encoded = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '=')
    const payload = JSON.parse(atob(padded)) as { exp?: number }
    return typeof payload.exp === 'number' ? payload.exp * 1000 : null
  } catch {
    return null
  }
}

function tokenExpired(token: string) {
  const expiresAt = tokenExpiresAt(token)
  return expiresAt === null || expiresAt <= Date.now()
}

function loadSession(): StoredSession | null {
  try {
    localStorage.removeItem(SESSION_KEY)
    const raw = sessionStorage.getItem(SESSION_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as StoredSession
    if (!parsed.token || tokenExpired(parsed.token)) {
      sessionStorage.removeItem(SESSION_KEY)
      return null
    }
    return { ...parsed, role: normalizeRole(parsed.role) }
  } catch {
    return null
  }
}

export function AuthProvider({ children }: PropsWithChildren) {
  const queryClient = useQueryClient()
  const [session, setSession] = useState<StoredSession | null>(() => {
    const restored = loadSession()
    setApiAccessToken(restored?.token || null)
    return restored
  })
  const [isRestoring, setIsRestoring] = useState(true)
  const [rememberedUsername, setRememberedUsername] = useState(
    () => localStorage.getItem(REMEMBERED_USERNAME_KEY) || '',
  )

  useEffect(() => {
    setApiAccessToken(session?.token || null)
  }, [session])

  const clearSession = useCallback(() => {
    localStorage.removeItem(SESSION_KEY)
    sessionStorage.removeItem(SESSION_KEY)
    setApiAccessToken(null)
    setSession(null)
    queryClient.clear()
  }, [queryClient])

  const refreshSession = useCallback(async () => {
    try {
      const response = await api.post<LoginResponse>('/auth/refresh')
      const nextSession: StoredSession = {
        username: response.username,
        token: response.token,
        role: normalizeRole(response.role),
      }
      sessionStorage.setItem(SESSION_KEY, JSON.stringify(nextSession))
      setApiAccessToken(nextSession.token)
      setSession(nextSession)
      return nextSession.token
    } catch {
      clearSession()
      return null
    }
  }, [clearSession])

  useEffect(() => {
    setApiAccessTokenRefresher(refreshSession)
    if (session) {
      setIsRestoring(false)
    } else {
      void refreshSession().finally(() => setIsRestoring(false))
    }
    return () => setApiAccessTokenRefresher(null)
    // Restore from the cookie once when the provider is mounted.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshSession])

  useEffect(() => {
    if (!session) return
    const expiresAt = tokenExpiresAt(session.token)
    if (expiresAt === null) return
    const refreshDelay = Math.max(0, expiresAt - Date.now() - 60_000)
    const timer = window.setTimeout(() => { void refreshSession() }, refreshDelay)
    return () => window.clearTimeout(timer)
  }, [session, refreshSession])

  const logout = useCallback(() => {
    void api.post<void>('/auth/logout').catch(() => undefined)
    clearSession()
  }, [clearSession])

  useEffect(() => {
    window.addEventListener('auth:unauthorized', logout)
    return () => window.removeEventListener('auth:unauthorized', logout)
  }, [logout])

  const login = useCallback(async (
    username: string,
    password: string,
    rememberUsername: boolean,
    autoLogin: boolean,
  ) => {
    const response = await api.post<LoginResponse>('/auth/login', { username, password, autoLogin })
    const nextSession: StoredSession = {
      username: response.username || username,
      token: response.token,
      role: normalizeRole(response.role),
    }
    localStorage.removeItem(SESSION_KEY)
    sessionStorage.removeItem(SESSION_KEY)
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(nextSession))
    if (rememberUsername) {
      localStorage.setItem(REMEMBERED_USERNAME_KEY, username)
      setRememberedUsername(username)
    } else {
      localStorage.removeItem(REMEMBERED_USERNAME_KEY)
      setRememberedUsername('')
    }
    setApiAccessToken(nextSession.token)
    setSession(nextSession)
  }, [])

  const value = useMemo<AuthContextValue>(() => ({
    user: session ? { username: session.username, role: session.role } : null,
    token: session?.token || null,
    isAuthenticated: Boolean(session),
    isAdmin: session?.role === 'ADMIN',
    canWrite: Boolean(session && session.role !== 'VIEWER'),
    isRestoring,
    rememberedUsername,
    login,
    logout,
  }), [session, isRestoring, rememberedUsername, login, logout])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
