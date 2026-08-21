import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, setApiAccessToken, setApiAccessTokenRefresher } from './client'

describe('API access token refresh', () => {
  afterEach(() => {
    setApiAccessToken(null)
    setApiAccessTokenRefresher(null)
    vi.restoreAllMocks()
  })

  it('refreshes once and retries a protected request after a 401', async () => {
    setApiAccessToken('expired-access')
    setApiAccessTokenRefresher(async () => {
      setApiAccessToken('new-access')
      return 'new-access'
    })
    const fetchMock = vi.spyOn(window, 'fetch')
      .mockResolvedValueOnce(new Response('{}', { status: 401, headers: { 'content-type': 'application/json' } }))
      .mockResolvedValueOnce(new Response('{"ok":true}', { status: 200, headers: { 'content-type': 'application/json' } }))

    await expect(api.get<{ ok: boolean }>('/protected')).resolves.toEqual({ ok: true })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Authorization')).toBe('Bearer expired-access')
    expect(new Headers(fetchMock.mock.calls[1][1]?.headers).get('Authorization')).toBe('Bearer new-access')
    expect(fetchMock.mock.calls[1][1]?.credentials).toBe('include')
  })
})
