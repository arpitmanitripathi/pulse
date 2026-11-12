import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api, ApiError } from '../api/client'
import type { CheckPage, Incident, Monitor, MonitorStats, StatsWindow } from '../api/types'
import { LatencyChart } from '../components/LatencyChart'
import { MonitorForm } from '../components/MonitorForm'
import { StateBadge } from '../components/StateBadge'
import { Button, Card, Empty, ErrorNote, Loading, Stat } from '../components/ui'
import { ERROR_LABELS, formatDuration, formatInterval, formatMs, formatTime, formatUptime } from '../lib/format'
import { REFRESH_MS, useApi } from '../lib/useApi'

const WINDOWS: { value: StatsWindow; label: string }[] = [
  { value: '24h', label: '24 hours' },
  { value: '7d', label: '7 days' },
  { value: '30d', label: '30 days' },
]

export function MonitorDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const monitor = useApi<Monitor>(`/api/monitors/${id}`, { refreshMs: REFRESH_MS })
  const [editing, setEditing] = useState(false)
  const [actionError, setActionError] = useState<string>()
  const [busy, setBusy] = useState(false)

  if (monitor.error?.status === 404) {
    return (
      <Empty>
        <p className="mb-3 font-medium text-slate-800">Monitor not found</p>
        <Link to="/monitors" className="font-medium text-emerald-700 hover:underline">
          Back to monitors
        </Link>
      </Empty>
    )
  }
  if (!monitor.data) return monitor.error ? <ErrorNote error={monitor.error} /> : <Loading />
  const m = monitor.data

  async function act(path: string, method: 'POST' | 'DELETE') {
    setBusy(true)
    setActionError(undefined)
    try {
      await api(path, { method })
      if (method === 'DELETE') navigate('/monitors', { replace: true })
      else monitor.reload()
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : 'Could not reach the server.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <Link to="/monitors" className="text-sm text-slate-500 hover:text-slate-800">
          ← Monitors
        </Link>
        <div className="mt-2 flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex items-center gap-3">
              <h1 className="truncate text-2xl font-semibold tracking-tight">{m.name}</h1>
              <StateBadge state={m.state} active={m.active} />
            </div>
            <p className="mt-1 break-all text-sm text-slate-600">
              {m.method} {m.url}
            </p>
            <p className="mt-1 text-xs text-slate-500">
              Every {formatInterval(m.intervalSeconds)} · timeout {formatMs(m.timeoutMs)} · {m.expectedStatus ? `expects ${m.expectedStatus}` : 'any 2xx is up'} · last
              checked {formatTime(m.lastCheckedAt)}
            </p>
          </div>
          {!editing && (
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" onClick={() => setEditing(true)}>
                Edit
              </Button>
              <Button variant="secondary" disabled={busy} onClick={() => void act(`/api/monitors/${m.id}/${m.active ? 'pause' : 'resume'}`, 'POST')}>
                {m.active ? 'Pause' : 'Resume'}
              </Button>
              <Button
                variant="danger"
                disabled={busy}
                onClick={() => {
                  if (window.confirm(`Delete "${m.name}" and all its history? This can't be undone.`)) void act(`/api/monitors/${m.id}`, 'DELETE')
                }}
              >
                Delete
              </Button>
            </div>
          )}
        </div>
      </div>
      <ErrorNote error={actionError} />

      {editing && (
        <Card>
          <h2 className="mb-4 font-semibold">Edit monitor</h2>
          <p className="mb-4 text-sm text-slate-600">Changing the URL starts the monitor fresh and closes an open incident.</p>
          <MonitorForm
            initial={m}
            submitLabel="Save changes"
            onSubmit={async (input) => {
              await api(`/api/monitors/${m.id}`, { method: 'PATCH', body: input })
              setEditing(false)
              monitor.reload()
            }}
            onCancel={() => setEditing(false)}
          />
        </Card>
      )}

      <StatsSection monitorId={m.id} />
      <IncidentsSection monitorId={m.id} />
      <ChecksSection monitorId={m.id} />
    </div>
  )
}

function StatsSection({ monitorId }: { monitorId: string }) {
  const [range, setRange] = useState<StatsWindow>('24h')
  const { data, error } = useApi<MonitorStats>(`/api/monitors/${monitorId}/stats?window=${range}`, { refreshMs: REFRESH_MS })
  return (
    <Card>
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-semibold">Uptime and response time</h2>
        <div role="tablist" aria-label="Time window" className="inline-flex rounded-lg border border-slate-200 p-0.5">
          {WINDOWS.map((w) => (
            <button
              key={w.value}
              type="button"
              role="tab"
              aria-selected={range === w.value}
              onClick={() => setRange(w.value)}
              className={`rounded-md px-3 py-1 text-sm ${range === w.value ? 'bg-slate-900 text-white' : 'text-slate-600 hover:text-slate-900'}`}
            >
              {w.label}
            </button>
          ))}
        </div>
      </div>
      <ErrorNote error={error} />
      {!data && !error && <Loading />}
      {data && (
        <>
          <dl className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Stat label="Uptime" value={formatUptime(data.uptime)} />
            <Stat label="p50 (typical)" value={formatMs(data.latencyP50)} />
            <Stat label="p95 (slowest 5%)" value={formatMs(data.latencyP95)} />
            <Stat label="Checks / failed" value={`${data.checks} / ${data.failures}`} />
          </dl>
          {data.checks === 0 ? <p className="py-8 text-center text-sm text-slate-500">No checks in this window yet.</p> : <LatencyChart points={data.series} daily={data.bucket === 'DAY'} />}
        </>
      )}
    </Card>
  )
}

function IncidentsSection({ monitorId }: { monitorId: string }) {
  const { data, error } = useApi<Incident[]>(`/api/incidents?monitorId=${monitorId}`, { refreshMs: REFRESH_MS })
  return (
    <Card>
      <h2 className="mb-4 font-semibold">Incidents</h2>
      <ErrorNote error={error} />
      {data && data.length === 0 && <p className="text-sm text-slate-500">No incidents. An incident opens after 3 failed checks in a row.</p>}
      {data && data.length > 0 && (
        <ul className="divide-y divide-slate-100">
          {data.slice(0, 10).map((i) => (
            <li key={i.id}>
              <Link to={`/incidents/${i.id}`} className="flex flex-wrap items-center justify-between gap-2 py-3 hover:bg-slate-50">
                <span className="flex items-center gap-2 text-sm">
                  <span className={`h-2 w-2 rounded-full ${i.resolvedAt ? 'bg-slate-300' : 'bg-red-500'}`} aria-hidden />
                  <span className="font-medium">{i.resolvedAt ? 'Resolved' : 'Ongoing'}</span>
                  <span className="text-slate-500">· {ERROR_LABELS[i.firstErrorType]}</span>
                </span>
                <span className="text-sm text-slate-500">
                  {formatTime(i.openedAt)} · {formatDuration(i.openedAt, i.resolvedAt)}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function ChecksSection({ monitorId }: { monitorId: string }) {
  const [size, setSize] = useState(25)
  const { data, error } = useApi<CheckPage>(`/api/monitors/${monitorId}/checks?size=${size}`, { refreshMs: REFRESH_MS })
  return (
    <Card>
      <h2 className="mb-4 font-semibold">Recent checks (24 h)</h2>
      <ErrorNote error={error} />
      {data && data.items.length === 0 && <p className="text-sm text-slate-500">No checks yet. The first one runs within a few seconds.</p>}
      {data && data.items.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="py-2 pr-4 font-medium">Time</th>
                <th className="py-2 pr-4 font-medium">Result</th>
                <th className="py-2 pr-4 font-medium">Status</th>
                <th className="py-2 pr-4 text-right font-medium">Response time</th>
                <th className="py-2 font-medium">Detail</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.items.map((c) => (
                <tr key={c.checkedAt}>
                  <td className="whitespace-nowrap py-2 pr-4 text-slate-600">{formatTime(c.checkedAt)}</td>
                  <td className={`py-2 pr-4 font-medium ${c.success ? 'text-emerald-700' : 'text-red-700'}`}>{c.success ? 'Up' : ERROR_LABELS[c.errorType]}</td>
                  <td className="py-2 pr-4 tabular-nums">{c.statusCode ?? '—'}</td>
                  <td className="py-2 pr-4 text-right tabular-nums">{formatMs(c.latencyMs)}</td>
                  <td className="py-2 text-slate-600">{c.errorMessage ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {data.hasMore && size < 200 && (
            <div className="mt-3">
              <Button variant="secondary" onClick={() => setSize((s) => Math.min(200, s * 2))}>
                Show more
              </Button>
            </div>
          )}
        </div>
      )}
    </Card>
  )
}
