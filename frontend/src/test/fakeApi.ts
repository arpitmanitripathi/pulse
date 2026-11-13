import { vi } from 'vitest'

type Reply = { status?: number; body?: unknown; headers?: Record<string, string> }
type Handler = (request: { method: string; path: string; body: unknown; headers: Record<string, string> }) => Reply | Promise<Reply>

/**
 * Replaces fetch with a tiny fake backend: routes are "METHOD /path" (query string included).
 * Every request is recorded in `calls`. An unknown route fails the test loudly.
 */
export function fakeApi(routes: Record<string, Handler | Reply>) {
  const calls: { method: string; path: string; body: unknown; headers: Record<string, string> }[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input)
    const method = init.method ?? 'GET'
    const headers = (init.headers ?? {}) as Record<string, string>
    const body = typeof init.body === 'string' ? JSON.parse(init.body) : undefined
    const request = { method, path, body, headers }
    calls.push(request)
    const route = routes[`${method} ${path}`]
    if (!route) throw new Error(`No fake route for ${method} ${path}`)
    const result = typeof route === 'function' ? await route(request) : route
    const status = result.status ?? 200
    return new Response(status === 204 || result.body === undefined ? null : JSON.stringify(result.body), {
      status,
      headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...result.headers },
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  return { calls, fetchMock }
}

export function problem(status: number, detail: string, errors?: Record<string, string>) {
  return { status, body: { type: 'about:blank', title: 'Error', status, detail, ...(errors ? { errors } : {}) } }
}
