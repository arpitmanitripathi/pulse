import { useParams } from 'react-router'
import type { PublicMonitor, PublicStatus } from '../api/types'
import { overall } from '../lib/status'
import { UptimeBars } from '../components/bars'
import { Logo } from '../components/Layout'
import { StateBadge } from '../components/StateBadge'
import { Loading } from '../components/ui'
import { formatDuration, formatMs, formatTime, formatUptime } from '../lib/format'
import { useApi } from '../lib/useApi'

/** The anonymous page at /s/:slug. No login, no session; refreshes every minute (the server caches 30 s). */
export function PublicStatusPage() {
  const { slug } = useParams()
  const { data, error } = useApi<PublicStatus>(`/api/public/status/${encodeURIComponent(slug ?? '')}`, { refreshMs: 60_000, isPublic: true })

  return (
    <div className="min-h-screen">
      <main className="mx-auto max-w-3xl px-4 py-10">
        {!data && error?.status === 404 && <Message title="Status page not found" text="Check the address, or ask the owner for the link." />}
        {!data && error && error.status !== 404 && <Message title="Status unavailable" text={error.message} />}
        {!data && !error && <Loading />}
        {data && <Status status={data} />}
        <footer className="mt-12 flex items-center justify-center gap-2 text-xs text-slate-400">
          Powered by <Logo />
        </footer>
      </main>
    </div>
  )
}

function Status({ status }: { status: PublicStatus }) {
  const banner = overall(status.monitors)
  const ongoing = status.incidents.filter((i) => !i.resolvedAt)
  return (
    <>
      <h1 className="mb-6 text-3xl font-semibold tracking-tight">{status.title}</h1>
      <div className={`mb-8 rounded-xl px-5 py-4 font-medium text-white ${banner.style}`} role="status">
        {banner.text}
        <span className="block text-xs font-normal text-white/80">Updated {formatTime(status.generatedAt)}</span>
      </div>

      {ongoing.length > 0 && (
        <section className="mb-8 rounded-xl border border-red-200 bg-red-50 p-5">
          <h2 className="mb-2 font-semibold text-red-900">Ongoing incidents</h2>
          <ul className="space-y-1 text-sm text-red-900">
            {ongoing.map((i) => (
              <li key={i.monitorName + i.openedAt}>
                {i.monitorName} is down since {formatTime(i.openedAt)} ({formatDuration(i.openedAt, null)})
              </li>
            ))}
          </ul>
        </section>
      )}

      {status.monitors.length === 0 ? (
        <p className="text-sm text-slate-500">No services listed on this page yet.</p>
      ) : (
        <ul className="space-y-4">
          {status.monitors.map((m) => (
            <li key={m.name} className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <h2 className="font-medium">{m.name}</h2>
                <StateBadge state={m.state} />
              </div>
              <UptimeBars days={m.days} />
              <dl className="mt-4 grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
                <Figure label="24 hours" value={formatUptime(m.uptime24h)} />
                <Figure label="7 days" value={formatUptime(m.uptime7d)} />
                <Figure label="30 days" value={formatUptime(m.uptime30d)} />
                <Figure label="Response time" value={formatMs(latest(m))} />
              </dl>
            </li>
          ))}
        </ul>
      )}

      <section className="mt-10">
        <h2 className="mb-3 font-semibold">Past incidents (30 days)</h2>
        {status.incidents.filter((i) => i.resolvedAt).length === 0 ? (
          <p className="text-sm text-slate-500">No incidents in the last 30 days.</p>
        ) : (
          <ul className="divide-y divide-slate-200 rounded-xl border border-slate-200 bg-white">
            {status.incidents
              .filter((i) => i.resolvedAt)
              .map((i) => (
                <li key={i.monitorName + i.openedAt} className="flex flex-wrap justify-between gap-2 px-4 py-3 text-sm">
                  <span className="font-medium">{i.monitorName}</span>
                  <span className="text-slate-500">
                    {formatTime(i.openedAt)} · down {formatDuration(i.openedAt, i.resolvedAt)}
                    {i.resolution === 'MONITOR_CHANGED' && ', then monitoring stopped'}
                  </span>
                </li>
              ))}
          </ul>
        )}
      </section>
    </>
  )
}

/** The most recent hour with a typical response time. */
function latest(m: PublicMonitor): number | null {
  for (let i = m.latency24h.length - 1; i >= 0; i--) {
    const p50 = m.latency24h[i]?.latencyP50
    if (p50 != null) return p50
  }
  return null
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className="font-semibold tabular-nums">{value}</dd>
    </div>
  )
}

function Message({ title, text }: { title: string; text: string }) {
  return (
    <div className="py-16 text-center">
      <h1 className="mb-2 text-xl font-semibold">{title}</h1>
      <p className="text-sm text-slate-600">{text}</p>
    </div>
  )
}
