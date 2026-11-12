import { Navigate, Outlet, useLocation } from 'react-router'
import { Loading } from '../components/ui'
import { useSession } from './session'

/** Logged-in pages: wait for the session check, then show the page or go to /login. */
export function RequireSession() {
  const { session } = useSession()
  const location = useLocation()
  if (session.status === 'loading') return <Loading label="Checking your session…" />
  if (session.status === 'anonymous') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  return <Outlet />
}

/**
 * Login and register: once signed in, go back to the page that sent you here (or the dashboard).
 * Only same-site paths are followed: "//evil.example" would leave the site.
 */
export function GuestOnly() {
  const { session } = useSession()
  const location = useLocation()
  if (session.status === 'loading') return <Loading label="Checking your session…" />
  if (session.status === 'signed-in') return <Navigate to={safeReturnPath(location.state)} replace />
  return <Outlet />
}

function safeReturnPath(state: unknown): string {
  const from = (state as { from?: unknown } | null)?.from
  return typeof from === 'string' && from.startsWith('/') && !from.startsWith('//') ? from : '/monitors'
}
