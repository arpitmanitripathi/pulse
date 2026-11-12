import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fakeApi, problem } from '../test/fakeApi'
import { api, ApiError, hasAccessToken, refreshSession, setAccessToken, setSessionLostHandler } from './client'

describe('api client', () => {
  beforeEach(() => {
    setAccessToken(null)
    setSessionLostHandler(() => {})
  })

  it('sends the access token and parses JSON', async () => {
    const { calls } = fakeApi({ 'GET /api/me': { body: { id: '1', email: 'a@b.com' } } })
    setAccessToken('t1')

    await expect(api('/api/me')).resolves.toEqual({ id: '1', email: 'a@b.com' })
    expect(calls[0]!.headers.Authorization).toBe('Bearer t1')
  })

  it('on 401 refreshes once and retries with the new token', async () => {
    const { calls } = fakeApi({
      'GET /api/monitors': (r) => (r.headers.Authorization === 'Bearer fresh' ? { body: [] } : { status: 401 }),
      'POST /api/auth/refresh': { body: { accessToken: 'fresh' } },
    })
    setAccessToken('expired')

    await expect(api('/api/monitors')).resolves.toEqual([])
    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual(['GET /api/monitors', 'POST /api/auth/refresh', 'GET /api/monitors'])
  })

  it('parallel 401s share ONE refresh (two would look like a stolen token and end every session)', async () => {
    let refreshes = 0
    fakeApi({
      'GET /api/monitors': (r) => (r.headers.Authorization === 'Bearer fresh' ? { body: [] } : { status: 401 }),
      'GET /api/alert-channels': (r) => (r.headers.Authorization === 'Bearer fresh' ? { body: [] } : { status: 401 }),
      'GET /api/status-pages': (r) => (r.headers.Authorization === 'Bearer fresh' ? { body: [] } : { status: 401 }),
      'POST /api/auth/refresh': async () => {
        refreshes++
        await new Promise((resolve) => setTimeout(resolve, 20))
        return { body: { accessToken: 'fresh' } }
      },
    })
    setAccessToken('expired')

    await Promise.all([api('/api/monitors'), api('/api/alert-channels'), api('/api/status-pages')])
    expect(refreshes).toBe(1)
  })

  it('refreshes are locked across tabs when the browser supports Web Locks', async () => {
    const request = vi.fn((_name: string, work: () => Promise<unknown>) => work())
    vi.stubGlobal('navigator', { locks: { request } })
    fakeApi({ 'POST /api/auth/refresh': { body: { accessToken: 'x' } } })

    await refreshSession()
    expect(request).toHaveBeenCalledWith('pulse-session-refresh', expect.any(Function))
  })

  it('when the refresh fails too, the session is reported lost', async () => {
    const lost = vi.fn()
    setSessionLostHandler(lost)
    fakeApi({ 'GET /api/me': { status: 401 }, 'POST /api/auth/refresh': { status: 401 } })
    setAccessToken('expired')

    await expect(api('/api/me')).rejects.toBeInstanceOf(ApiError)
    expect(lost).toHaveBeenCalledOnce()
    expect(hasAccessToken()).toBe(false)
  })

  it('a wrong password is not treated as an expired session', async () => {
    const { calls } = fakeApi({ 'POST /api/auth/login': problem(401, 'Wrong email or password.') })

    await expect(api('/api/auth/login', { method: 'POST', body: {} })).rejects.toThrow('Wrong email or password.')
    expect(calls).toHaveLength(1) // no refresh attempt
  })

  it('turns problem+json into an ApiError with field errors', async () => {
    fakeApi({ 'POST /api/monitors': problem(400, 'One or more fields are invalid.', { url: 'That address is not allowed.' }) })
    setAccessToken('t')

    const error = await api('/api/monitors', { method: 'POST', body: {} }).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(400)
    expect((error as ApiError).fieldErrors).toEqual({ url: 'That address is not allowed.' })
  })

  it('204 resolves to undefined', async () => {
    fakeApi({ 'DELETE /api/monitors/1': { status: 204 } })
    setAccessToken('t')
    await expect(api('/api/monitors/1', { method: 'DELETE' })).resolves.toBeUndefined()
  })
})
