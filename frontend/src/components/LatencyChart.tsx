import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { formatDay, formatHour, formatMs } from '../lib/format'

interface LatencyPoint {
  start: string
  latencyP50: number | null
  latencyP95: number | null
}

/** p50 and p95 response time over time (Recharts: loaded only with the monitor page). Gaps (no successful checks) stay gaps. */
export function LatencyChart({ points, daily = false }: { points: LatencyPoint[]; daily?: boolean }) {
  const label = daily ? formatDay : formatHour
  if (!points.some((p) => p.latencyP50 != null)) {
    return <p className="py-8 text-center text-sm text-slate-500">No successful checks in this window, so there is no response time to show.</p>
  }
  return (
    <div className="h-64" aria-label="Response time chart">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={points} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <CartesianGrid stroke="#e2e8f0" vertical={false} />
          <XAxis dataKey="start" tickFormatter={label} tick={{ fontSize: 12, fill: '#64748b' }} minTickGap={32} />
          <YAxis tickFormatter={(v: number) => formatMs(v)} tick={{ fontSize: 12, fill: '#64748b' }} width={64} />
          <Tooltip
            labelFormatter={(v) => (daily ? formatDay(String(v)) : new Date(String(v)).toLocaleString())}
            formatter={(v) => formatMs(typeof v === 'number' ? v : null)}
          />
          <Legend />
          <Line type="monotone" dataKey="latencyP50" name="p50 (typical)" stroke="#10b981" strokeWidth={2} dot={{ r: 2 }} connectNulls={false} isAnimationActive={false} />
          <Line type="monotone" dataKey="latencyP95" name="p95 (slowest 5%)" stroke="#f59e0b" strokeWidth={2} dot={{ r: 2 }} connectNulls={false} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
