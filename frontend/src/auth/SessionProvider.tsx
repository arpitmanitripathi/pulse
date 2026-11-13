import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, refreshSession, setAccessToken, setSessionLostHandler } from '../api/client'
import type { User } from '../api/types'
import { SessionContext, type Session } from './session'

/** Restores the session from the refresh cookie on load, and offers login / register / logout. */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session>({ status: 'loading' })

  useEffect(() => {
    setSessionLostHandler(() => setSession({ status: 'anonymous' }))
    let cancelled = false
    refreshSession()
      .then(async (ok) => (ok ? await api<User>('/api/me') : null))
      .catch(() => null)
      .then((user) => {
        if (!cancelled) setSession(user ? { status: 'signed-in', user } : { status: 'anonymous' })
      })
    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const tokens = await api<{ accessToken: string }>('/api/auth/login', {
      method: 'POST',
      body: { email, password },
    })
    setAccessToken(tokens.accessToken)
    setSession({ status: 'signed-in', user: await api<User>('/api/me') })
  }, [])

  const register = useCallback(
    async (email: string, password: string) => {
      await api('/api/auth/register', { method: 'POST', body: { email, password } })
      await login(email, password)
    },
    [login],
  )

  const logout = useCallback(async () => {
    try {
      await api('/api/auth/logout', { method: 'POST' })
    } finally {
      setAccessToken(null)
      setSession({ status: 'anonymous', loggedOut: true })
    }
  }, [])

  const value = useMemo(() => ({ session, login, register, logout }), [session, login, register, logout])
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}
