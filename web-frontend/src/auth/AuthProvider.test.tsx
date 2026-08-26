import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
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
    cleanup()
    sessionStorage.clear()
    localStorage.clear()
    setApiAccessToken(null)
    vi.useRealTimers()
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

  it('keeps the current browser session when refresh fails transiently', async () => {
    const token = jwt(Math.floor(Date.now() / 1000) + 1)
    sessionStorage.setItem('classroom-dashboard.session', JSON.stringify({ username: 'admin', role: 'ADMIN', token }))
    vi.spyOn(window, 'fetch').mockRejectedValue(new TypeError('network unavailable'))

    render(<QueryClientProvider client={new QueryClient()}><AuthProvider><SessionProbe /></AuthProvider></QueryClientProvider>)

    await screen.findByText('admin')
    await new Promise((resolve) => window.setTimeout(resolve, 10))
    expect(sessionStorage.getItem('classroom-dashboard.session')).toContain('admin')
  })

  it('retries a scheduled token refresh after a transient failure', async () => {
    vi.useFakeTimers()
    const currentToken = jwt(Math.floor(Date.now() / 1000) + 120)
    const refreshedToken = jwt(Math.floor(Date.now() / 1000) + 3_600)
    sessionStorage.setItem('classroom-dashboard.session', JSON.stringify({ username: 'admin', role: 'ADMIN', token: currentToken }))
    const fetchMock = vi.spyOn(window, 'fetch')
      .mockRejectedValueOnce(new TypeError('network unavailable'))
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ username: 'admin', role: 'ADMIN', token: refreshedToken }),
        { headers: { 'content-type': 'application/json' } },
      ))

    render(<QueryClientProvider client={new QueryClient()}><AuthProvider><SessionProbe /></AuthProvider></QueryClientProvider>)
    expect(screen.getByText('admin')).toBeTruthy()

    await act(async () => { await vi.advanceTimersByTimeAsync(61_000) })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem('classroom-dashboard.session')).toContain(currentToken)

    await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(sessionStorage.getItem('classroom-dashboard.session')).toContain(refreshedToken)
  })

  it('retries cookie restoration while the server is temporarily unavailable', async () => {
    vi.useFakeTimers()
    const token = jwt(Math.floor(Date.now() / 1000) + 3_600)
    const fetchMock = vi.spyOn(window, 'fetch')
      .mockRejectedValueOnce(new TypeError('network unavailable'))
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ username: 'operator', role: 'USER', token }),
        { headers: { 'content-type': 'application/json' } },
      ))

    render(<QueryClientProvider client={new QueryClient()}><AuthProvider><SessionProbe /></AuthProvider></QueryClientProvider>)
    expect(screen.getByText('restoring')).toBeTruthy()
    await act(async () => { await Promise.resolve() })

    await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(screen.getByText('operator')).toBeTruthy()
  })
})
