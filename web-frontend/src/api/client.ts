const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')
const REQUEST_TIMEOUT_MS = 20_000

export class ApiError extends Error {
  readonly status: number
  readonly details: unknown

  constructor(message: string, status: number, details?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.details = details
  }
}

let accessToken: string | null = null
let accessTokenRefresher: (() => Promise<string | null>) | null = null
let refreshInFlight: Promise<string | null> | null = null

export function setApiAccessToken(token: string | null) {
  accessToken = token
}

export function setApiAccessTokenRefresher(refresher: (() => Promise<string | null>) | null) {
  accessTokenRefresher = refresher
}

export function getApiBaseUrl() {
  return API_BASE_URL
}

function extractErrorMessage(body: unknown, fallback: string) {
  if (!body || typeof body !== 'object') return fallback
  const candidate = body as Record<string, unknown>
  for (const key of ['message', 'error', 'detail']) {
    if (typeof candidate[key] === 'string' && candidate[key]) return candidate[key]
  }
  return fallback
}

async function parseResponse(response: Response) {
  if (response.status === 204) return undefined
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) return response.json()
  return response.text()
}

function isSessionEndpoint(path: string) {
  const cleanPath = path.split('?')[0].replace(/\/$/, '')
  return cleanPath === '/auth/login' || cleanPath === '/auth/refresh' || cleanPath === '/auth/logout'
}

function acceptsAccessToken(path: string) {
  const cleanPath = path.split('?')[0].replace(/\/$/, '')
  return cleanPath !== '/auth/login' && cleanPath !== '/auth/refresh'
}

async function executeFetch(path: string, init: RequestInit = {}) {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  const headers = new Headers(init.headers)
  if (accessToken && acceptsAccessToken(path)) headers.set('Authorization', `Bearer ${accessToken}`)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  try {
    const response = await fetch(`${API_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`, {
      ...init,
      headers,
      credentials: 'include',
      signal: controller.signal,
    })
    return response
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiError('서버 응답 시간이 초과되었습니다.', 0)
    }
    throw new ApiError('서버에 연결할 수 없습니다.', 0, error)
  } finally {
    window.clearTimeout(timeout)
  }
}

async function refreshAccessToken() {
  if (!accessTokenRefresher) return null
  if (!refreshInFlight) {
    refreshInFlight = accessTokenRefresher().finally(() => { refreshInFlight = null })
  }
  return refreshInFlight
}

async function fetchWithAuth(path: string, init: RequestInit = {}) {
  let response = await executeFetch(path, init)
  if (response.status === 401 && accessToken && !isSessionEndpoint(path)) {
    const refreshedToken = await refreshAccessToken()
    if (refreshedToken) response = await executeFetch(path, init)
  }
  if (response.status === 401 && !isSessionEndpoint(path)) {
    window.dispatchEvent(new CustomEvent('auth:unauthorized'))
  }
  return response
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  try {
    const response = await fetchWithAuth(path, init)
    const body = await parseResponse(response)
    if (!response.ok) {
      const error = new ApiError(
        extractErrorMessage(body, `요청을 처리하지 못했습니다. (${response.status})`),
        response.status,
        body,
      )
      throw error
    }
    return body as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw error
  }
}

export async function downloadFile(path: string, fallbackName: string) {
  const response = await fetchWithAuth(path)
  if (!response.ok) {
    const body = await parseResponse(response)
    throw new ApiError(
      extractErrorMessage(body, `파일을 내려받지 못했습니다. (${response.status})`),
      response.status,
      body,
    )
  }
  const disposition = response.headers.get('content-disposition') || ''
  const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const plainName = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  const filename = encodedName ? decodeURIComponent(encodedName) : plainName || fallbackName
  const url = URL.createObjectURL(await response.blob())
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1_000)
}

export const api = {
  get: <T>(path: string) => apiRequest<T>(path),
  post: <T>(path: string, body?: unknown) => apiRequest<T>(path, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
  }),
  put: <T>(path: string, body?: unknown) => apiRequest<T>(path, {
    method: 'PUT',
    body: body === undefined ? undefined : JSON.stringify(body),
  }),
  patch: <T>(path: string, body?: unknown) => apiRequest<T>(path, {
    method: 'PATCH',
    body: body === undefined ? undefined : JSON.stringify(body),
  }),
  delete: <T>(path: string) => apiRequest<T>(path, { method: 'DELETE' }),
  upload: <T>(path: string, formData: FormData) => apiRequest<T>(path, {
    method: 'POST',
    body: formData,
  }),
  download: downloadFile,
}

export function extractList<T>(payload: T[] | { content?: T[]; data?: T[] }): T[] {
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload.content)) return payload.content
  if (Array.isArray(payload.data)) return payload.data
  return []
}
