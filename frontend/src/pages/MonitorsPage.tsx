import { Link } from 'react-router'
import type { Monitor, MonitorStats } from '../api/types'
import { Sparkline } from '../components/bars'
import { StateBadge } from '../components/StateBadge'
import { Empty, ErrorNote, Loading, PageTitle } from '../components/ui'
import { formatInterval, formatUptime } from '../lib/format'
import { REFRESH_MS, useApi } from '../lib/useApi'

export const MAX_MONITORS = 20

export function MonitorsPage() {
  const { data: monitors, error, loading } = useApi<Monitor[]>('/api/monitors', { refreshMs: REFRESH_MS })
  const full = (monitors?.length ?? 0) >= MAX_MONITORS

  return (
    <>
      <PageTitle
        actions={
          full ? (
            <span className="text-sm text-slate-500">20 of 20 monitors used</span>
          ) : (
            <Link to="/monitors/new" className="rounded-lg bg-emerald-600 px-3.5 py-2 text-sm font-medium text-white hover:bg-emerald-700">
              Add monitor
            </Link>
          )
        }
      >
        Monitors
      </PageTitle>
      <ErrorNote error={error} />
      {loading && !monitors && <Loading />}
      {monitors && monitors.length === 0 && (
        <Empty>
          <p className="mb-3 font-medium text-slate-800">No monitors yet</p>
          <p className="mb-4">Add a URL and Pulse will check it every 30 seconds to 5 minutes.</p>
          <Link to="/monitors/new" className="font-medium text-emerald-700 hover:underline">
            Add your first monitor
          </Link>
        </Empty>
      )}
      {monitors && monitors.length > 0 && (
        <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-3 font-medium">Monitor</th>
                <th className="px-4 py-3 font-medium">State</th>
                <th className="px-4 py-3 text-right font-medium">Uptime 24 h</th>
                <th className="px-4 py-3 font-medium">Response time 24 h</th>
                <th className="px-4 py-3 font-medium">Every</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {monitors.map((m) => (
                <tr key={m.id} className="hover:bg-slate-50">
                  <td className="max-w-xs px-4 py-3">
                    <Link to={`/monitors/${m.id}`} className="font-medium text-slate-900 hover:text-emerald-700">
                      {m.name}
                    </Link>
                    <div className="truncate text-xs text-slate-500">{m.url}</div>
                  </td>
                  <td className="px-4 py-3">
                    <StateBadge state={m.state} active={m.active} />
                  </td>
                  <td className="px-4 py-3 text-right tabular-nums">{formatUptime(m.uptime24h)}</td>
                  <td className="px-4 py-3">
                    <MonitorSparkline id={m.id} />
                  </td>
                  <td className="px-4 py-3 text-slate-600">{formatInterval(m.intervalSeconds)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function MonitorSparkline({ id }: { id: string }) {
  const { data } = useApi<MonitorStats>(`/api/monitors/${id}/stats?window=24h`, { refreshMs: REFRESH_MS })
  if (!data) return <span className="text-xs text-slate-400">…</span>
  return <Sparkline values={data.series.map((p) => p.latencyP50)} />
}
