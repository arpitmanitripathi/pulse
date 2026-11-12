import { NavLink, Outlet } from 'react-router'
import { useSession } from '../auth/session'

const links = [
  { to: '/monitors', label: 'Monitors' },
  { to: '/alerts', label: 'Alerts' },
  { to: '/status-pages', label: 'Status pages' },
]

export function Logo() {
  return (
    <span className="flex items-center gap-2 text-lg font-semibold tracking-tight">
      <svg viewBox="0 0 32 32" className="h-6 w-6" aria-hidden>
        <path d="M2 17h7l3-8 5 15 4-11 2 4h7" fill="none" stroke="#10b981" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      Pulse
    </span>
  )
}

/** Header with navigation for logged-in pages. */
export function Layout() {
  const { session, logout } = useSession()
  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-8 gap-y-2 px-4 py-3">
          <NavLink to="/monitors" aria-label="Pulse home">
            <Logo />
          </NavLink>
          <nav className="flex gap-1" aria-label="Main">
            {links.map((l) => (
              <NavLink
                key={l.to}
                to={l.to}
                className={({ isActive }) =>
                  `rounded-md px-3 py-1.5 text-sm font-medium ${isActive ? 'bg-slate-100 text-slate-900' : 'text-slate-600 hover:text-slate-900'}`
                }
              >
                {l.label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-3 text-sm">
            {session.status === 'signed-in' && <span className="hidden text-slate-500 sm:inline">{session.user.email}</span>}
            <button type="button" onClick={() => void logout()} className="font-medium text-slate-600 hover:text-slate-900">
              Log out
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}
