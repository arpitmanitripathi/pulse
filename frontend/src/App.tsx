import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { GuestOnly, RequireSession } from './auth/RequireSession'
import { SessionProvider } from './auth/SessionProvider'
import { Layout } from './components/Layout'
import { AlertsPage } from './pages/AlertsPage'
import { AuthPage } from './pages/AuthPage'
import { IncidentPage } from './pages/IncidentPage'
import { MonitorsPage } from './pages/MonitorsPage'
import { NewMonitorPage } from './pages/NewMonitorPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { PublicStatusPage } from './pages/PublicStatusPage'
import { StatusPagesPage } from './pages/StatusPagesPage'
import { Loading } from './components/ui'

// The only page with charts: its code (and the chart library) loads when it is first opened.
const MonitorDetailPage = lazy(() => import('./pages/MonitorDetailPage').then((m) => ({ default: m.MonitorDetailPage })))

export function App() {
  return (
    <Routes>
      {/* Public status pages never touch the session. */}
      <Route path="/s/:slug" element={<PublicStatusPage />} />
      <Route path="*" element={<SessionRoutes />} />
    </Routes>
  )
}

function SessionRoutes() {
  return (
    <SessionProvider>
      <Routes>
        <Route element={<GuestOnly />}>
          <Route path="/login" element={<AuthPage mode="login" />} />
          <Route path="/register" element={<AuthPage mode="register" />} />
        </Route>
        <Route element={<RequireSession />}>
          <Route element={<Layout />}>
            <Route path="/" element={<Navigate to="/monitors" replace />} />
            <Route path="/monitors" element={<MonitorsPage />} />
            <Route path="/monitors/new" element={<NewMonitorPage />} />
            <Route path="/monitors/:id" element={<Suspense fallback={<Loading />}><MonitorDetailPage /></Suspense>} />
            <Route path="/incidents/:id" element={<IncidentPage />} />
            <Route path="/alerts" element={<AlertsPage />} />
            <Route path="/status-pages" element={<StatusPagesPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Route>
      </Routes>
    </SessionProvider>
  )
}
