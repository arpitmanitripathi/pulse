/**
 * Every call to the backend goes through here.
 *
 * - The access token is kept in memory only, never in localStorage.
 * - A 401 triggers one refresh (the httpOnly cookie is sent automatically) and one retry.
 * - Refreshes are single-flight within the tab and locked across tabs: two refreshes with
 *   the same cookie look like a stolen token to the backend, which then ends every session.
 * - Errors arrive as RFC 9457 problem+json and become an ApiError.
 */

export class ApiError extends Error {
  readonly status: number
  /** Field name → message, for 400 validation errors. */
  readonly fieldErrors: Record<string, string>
  /** From the Retry-After header of a 429, in seconds. */
  readonly retryAfterSeconds: number | undefined

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}, retryAfterSeconds?: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
    this.retryAfterSeconds = retryAfterSeconds
  }
}

let accessToken: string | null = null
let refreshing: Promise<boolean> | null = null
let onSessionLost: () => void = () => {}

export function setAccessToken(token: string | null) {
  accessToken = token
}

export function hasAccessToken() {
  return accessToken !== null
}

/** Called when the session is gone for good (refresh failed); the app shows the login page. */
export function setSessionLostHandler(handler: () => void) {
  onSessionLost = handler
}

/** Swaps the refresh cookie for a new access token. Resolves false if there is no valid session. */
export function refreshSession(): Promise<boolean> {
  refreshing ??= acrossTabs(async () => {
    const response = await fetch('/api/auth/refresh', { method: 'POST' })
    if (!response.ok) {
      accessToken = null
      return false
    }
    accessToken = ((await response.json()) as { accessToken: string }).accessToken
    return true
  }).finally(() => {
    refreshing = null
  })
  return refreshing
}

function acrossTabs<T>(work: () => Promise<T>): Promise<T> {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined
  return locks ? locks.request('pulse-session-refresh', work) : work()
}

type Method = 'GET' | 'POST' | 'PATCH' | 'DELETE'

/** Calls the API and returns the parsed JSON body (undefined for 204). */
export async function api<T>(path: string, options: { method?: Method; body?: unknown; signal?: AbortSignal } = {}) {
  const isAuthCall = path.startsWith('/api/auth/')
  let response = await send(path, options)
  if (response.status === 401 && !isAuthCall) {
    if (await refreshSession()) {
      response = await send(path, options)
    }
    if (response.status === 401) {
      onSessionLost()
    }
  }
  return parse<T>(response)
}

/** For the public status page: no token, no refresh. */
export async function publicApi<T>(path: string, signal?: AbortSignal) {
  return parse<T>(await fetch(path, { signal }))
}

function send(path: string, { method = 'GET', body, signal }: { method?: Method; body?: unknown; signal?: AbortSignal }) {
  const headers: Record<string, string> = {}
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  return fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal })
}

async function parse<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T
  const text = await response.text()
  let json: unknown = undefined
  try {
    json = text ? JSON.parse(text) : undefined
  } catch {
    // not JSON (for example a proxy error page)
  }
  if (response.ok) return json as T
  const problem = (json ?? {}) as { detail?: string; title?: string; errors?: Record<string, string> }
  const message = problem.detail ?? problem.title ?? `Request failed (HTTP ${response.status}).`
  const retryAfter = Number(response.headers.get('Retry-After'))
  throw new ApiError(response.status, message, problem.errors ?? {}, Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined)
}
