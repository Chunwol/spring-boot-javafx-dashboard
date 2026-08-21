import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, setApiAccessToken } from '../api/client'
import { AuthProvider, useAuth } from './AuthProvider'

function jwt(exp: number) {
  const encode = (value: object) => btoa(JSON.stringify(value)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `${encode({ alg: 'none' })}.${encode({ exp })}.signature`
}

function ImmediateProbe() {
  useState(() => {
    void api.get('/probe')
    return true
  })
  return null
}

function SessionProbe() {
  const { user, isRestoring } = useAuth()
  return <span>{isRestoring ? 'restoring' : user?.username || 'anonymous'}</span>
}

describe('AuthProvider session restoration', () => {
  afterEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    setApiAccessToken(null)
    vi.restoreAllMocks()
  })

  it('sets the API token before authenticated children can start requests', async () => {
    const token = jwt(Math.floor(Date.now() / 1000) + 3_600)
    sessionStorage.setItem('classroom-dashboard.session', JSON.stringify({ username: 'admin', role: 'ADMIN', token }))
    const fetchMock = vi.spyOn(window, 'fetch').mockResolvedValue(new Response('{}', { headers: { 'content-type': 'application/json' } }))

    render(<QueryClientProvider client={new QueryClient()}><AuthProvider><ImmediateProbe /></AuthProvider></QueryClientProvider>)

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    for (const [, init] of fetchMock.mock.calls) {
      expect(new Headers(init?.headers).get('Authorization')).toBe(`Bearer ${token}`)
    }
  })

  it('restores a browser session from the HttpOnly refresh cookie', async () => {
    const token = jwt(Math.floor(Date.now() / 1000) + 3_600)
    const fetchMock = vi.spyOn(window, 'fetch').mockResolvedValue(new Response(
      JSON.stringify({ username: 'operator', role: 'USER', token }),
      { headers: { 'content-type': 'application/json' } },
    ))

    render(<QueryClientProvider client={new QueryClient()}><AuthProvider><SessionProbe /></AuthProvider></QueryClientProvider>)

    await screen.findByText('operator')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/auth/refresh')
    expect(init?.credentials).toBe('include')
    expect(new Headers(init?.headers).has('Authorization')).toBe(false)
  })
})
