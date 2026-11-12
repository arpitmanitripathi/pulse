import { Link, useParams } from 'react-router'
import type { Delivery, Incident } from '../api/types'
import { Card, Empty, ErrorNote, Loading } from '../components/ui'
import { ERROR_LABELS, formatDuration, formatTime } from '../lib/format'
import { REFRESH_MS, useApi } from '../lib/useApi'

export function IncidentPage() {
  const { id } = useParams()
  const { data: incident, error } = useApi<Incident>(`/api/incidents/${id}`, { refreshMs: REFRESH_MS })

  if (error?.status === 404) {
    return (
      <Empty>
        <p className="mb-3 font-medium text-slate-800">Incident not found</p>
        <Link to="/monitors" className="font-medium text-emerald-700 hover:underline">
          Back to monitors
        </Link>
      </Empty>
    )
  }
  if (!incident) return error ? <ErrorNote error={error} /> : <Loading />

  const events = timeline(incident)
  return (
    <div className="max-w-3xl space-y-6">
      <div>
        <Link to={`/monitors/${incident.monitorId}`} className="text-sm text-slate-500 hover:text-slate-800">
          ← {incident.monitorName}
        </Link>
        <h1 className="mt-2 text-2xl font-semibold tracking-tight">
          {incident.resolvedAt ? 'Resolved incident' : 'Ongoing incident'}: {incident.monitorName}
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          {ERROR_LABELS[incident.firstErrorType]} · started {formatTime(incident.openedAt)} · {incident.resolvedAt ? 'lasted' : 'so far'}{' '}
          {formatDuration(incident.openedAt, incident.resolvedAt)}
        </p>
      </div>

      <Card>
        <h2 className="mb-3 font-semibold">What happened</h2>
        <Summary incident={incident} />
      </Card>

      <Card>
        <h2 className="mb-4 font-semibold">Timeline</h2>
        <ol className="relative space-y-5 border-l border-slate-200 pl-6">
          {events.map((e, i) => (
            <li key={i} className="relative">
              <span className={`absolute -left-[31px] top-1 h-3 w-3 rounded-full ring-4 ring-white ${e.color}`} aria-hidden />
              <p className="text-sm font-medium">{e.title}</p>
              <p className="text-xs text-slate-500">{e.time ? formatTime(e.time) : 'not sent yet'}</p>
              {e.detail && <p className="mt-1 text-sm text-slate-600">{e.detail}</p>}
            </li>
          ))}
        </ol>
      </Card>
    </div>
  )
}

/** The AI text is plain text: React escapes it, so it can never become HTML. */
function Summary({ incident }: { incident: Incident }) {
  switch (incident.summaryStatus) {
    case 'DONE':
      return (
        <>
          <p className="text-slate-800">{incident.summary}</p>
          <p className="mt-2 text-xs text-slate-500">Written by AI from the last 20 checks. It can be wrong; the check data below is the record.</p>
        </>
      )
    case 'PENDING':
      return <p className="text-sm text-slate-500">Writing a summary… (usually a few seconds)</p>
    case 'FAILED':
      return <p className="text-sm text-slate-500">A summary could not be written this time. Alerts were not affected.</p>
    case 'SKIPPED':
      return <p className="text-sm text-slate-500">AI summaries are switched off on this server (no API key).</p>
  }
}

interface Event {
  title: string
  time: string | null
  detail?: string
  color: string
}

function timeline(incident: Incident): Event[] {
  const events: Event[] = [
    { title: `Marked down: ${ERROR_LABELS[incident.firstErrorType]} (3 failed checks in a row)`, time: incident.openedAt, color: 'bg-red-500' },
  ]
  const deliveries = incident.alerts ?? []
  for (const d of deliveries.filter((x) => x.kind === 'DOWN')) events.push(alertEvent(d))
  if (incident.resolvedAt) {
    events.push({ title: 'Recovered (2 successful checks in a row)', time: incident.resolvedAt, color: 'bg-emerald-500' })
  }
  for (const d of deliveries.filter((x) => x.kind === 'RECOVERED')) events.push(alertEvent(d))
  if (deliveries.length === 0) {
    events.splice(1, 0, { title: 'No alerts sent: no alert channels were set up', time: incident.openedAt, color: 'bg-slate-300' })
  }
  return events
}

function alertEvent(d: Delivery): Event {
  const what = `${d.kind === 'DOWN' ? 'Down' : 'Recovered'} alert to ${d.channelName} (${d.type.toLowerCase()})`
  if (d.sentAt) return { title: `${what}: sent`, time: d.sentAt, color: 'bg-sky-500' }
  return {
    title: `${what}: ${d.attempts >= 6 ? 'gave up' : d.attempts === 0 ? 'queued' : 'retrying'}`,
    time: null,
    detail: d.lastError ? `${d.attempts} attempt${d.attempts === 1 ? '' : 's'}. Last error: ${d.lastError}` : undefined,
    color: d.attempts >= 6 ? 'bg-red-300' : 'bg-amber-400',
  }
}
