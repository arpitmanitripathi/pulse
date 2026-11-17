import { useEffect, useRef, useState } from 'react'
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

/** "2026-11-15" → "15 Nov 2026" (it is a UTC day, so it is formatted in UTC, not the reader's zone). */
function formatUtcDay(date: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return date
  return new Date(`${date}T00:00:00Z`).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
}

/**
 * 90 daily bars (UTC days), oldest first. Hovering or tapping a bar shows its day and uptime at once
 * (the browser's own title tooltip appears only after a delay and never on touch screens).
 */
export function UptimeBars({ days }: { days: { date: string; uptime: number | null }[] }) {
  const [active, setActive] = useState<number | null>(null)
  const box = useRef<HTMLDivElement>(null)

  // On touch screens there is no "mouse leave": a tap anywhere else closes the tooltip.
  useEffect(() => {
    if (active === null) return
    const close = (e: PointerEvent) => {
      if (!box.current?.contains(e.target as Node)) setActive(null)
    }
    document.addEventListener('pointerdown', close)
    return () => document.removeEventListener('pointerdown', close)
  }, [active])

  const day = active === null ? undefined : days[active]
  // Slide the tooltip from left-aligned (first bar) to right-aligned (last bar), so it never leaves the card.
  const at = active === null ? 0 : (active + 0.5) / days.length

  return (
    <div ref={box} className="relative" onMouseLeave={() => setActive(null)}>
      {day && (
        <div
          role="tooltip"
          className="pointer-events-none absolute bottom-full z-10 mb-2 whitespace-nowrap rounded-md bg-slate-900 px-2.5 py-1.5 text-xs text-white shadow-lg"
          style={{ left: `${at * 100}%`, transform: `translateX(-${at * 100}%)` }}
        >
          <span className="font-medium">{formatUtcDay(day.date)}</span>
          <span className="text-slate-300"> (UTC) · </span>
          {day.uptime == null ? 'no data' : `${formatUptime(day.uptime)} uptime`}
        </div>
      )}
      <ol className="flex h-8 items-stretch gap-px" aria-label="Daily uptime, last 90 days (UTC)">
        {days.map((d, i) => (
          <li
            key={d.date}
            className={`flex-1 cursor-pointer rounded-[1px] ${barColor(d.uptime)} ${active === i ? 'opacity-60' : ''}`}
            aria-label={`${d.date}: ${d.uptime == null ? 'no data' : formatUptime(d.uptime)}`}
            onMouseEnter={() => setActive(i)}
            onClick={() => setActive(i)}
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
