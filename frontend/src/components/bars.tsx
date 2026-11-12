import { formatMs, formatUptime } from '../lib/format'
import { barColor } from '../lib/status'

// Plain SVG and divs: no chart library, so the list and the public page stay small.

/** A tiny p50 line for the monitor list. */
export function Sparkline({ values }: { values: (number | null)[] }) {
  const present = values.filter((v): v is number => v != null)
  if (present.length === 0) return <span className="text-xs text-slate-400">no data yet</span>
  if (present.length === 1) return <span className="text-sm tabular-nums text-slate-700">{formatMs(present[0])}</span>
  const max = Math.max(...present)
  const min = Math.min(...present)
  const span = max - min || 1
  const step = 100 / Math.max(1, values.length - 1)
  let path = ''
  let pen = false
  values.forEach((v, i) => {
    if (v == null) {
      pen = false
      return
    }
    const x = (i * step).toFixed(1)
    const y = (28 - ((v - min) / span) * 24).toFixed(1)
    path += `${pen ? 'L' : 'M'}${x} ${y} `
    pen = true
  })
  return (
    <svg viewBox="0 0 100 30" className="h-8 w-28" preserveAspectRatio="none" role="img" aria-label={`Response time, ${formatMs(present[0])} to ${formatMs(present[present.length - 1])}`}>
      <path d={path} fill="none" stroke="#10b981" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
    </svg>
  )
}

/** 90 daily bars (UTC days), oldest first. */
export function UptimeBars({ days }: { days: { date: string; uptime: number | null }[] }) {
  return (
    <div>
      <ol className="flex h-8 items-stretch gap-px" aria-label="Daily uptime, last 90 days (UTC)">
        {days.map((d) => (
          <li
            key={d.date}
            className={`flex-1 rounded-[1px] ${barColor(d.uptime)}`}
            title={`${d.date} (UTC): ${d.uptime == null ? 'no data' : formatUptime(d.uptime)}`}
            aria-label={`${d.date}: ${d.uptime == null ? 'no data' : formatUptime(d.uptime)}`}
          />
        ))}
      </ol>
      <div className="mt-1 flex justify-between text-xs text-slate-500">
        <span>90 days ago</span>
        <span>Today (UTC)</span>
      </div>
    </div>
  )
}
