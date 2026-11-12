import { createContext, useContext } from 'react'
import type { User } from '../api/types'

export type Session =
  | { status: 'loading' }
  /** `loggedOut`: the user chose to log out, so the next login starts fresh instead of returning to the old page. */
  | { status: 'anonymous'; loggedOut?: boolean }
  | { status: 'signed-in'; user: User }

export interface SessionApi {
  session: Session
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

export const SessionContext = createContext<SessionApi | null>(null)

export function useSession(): SessionApi {
  const value = useContext(SessionContext)
  if (!value) throw new Error('useSession must be used inside <SessionProvider>')
  return value
}
