import type { PublicMonitor } from '../api/types'

/** Same rule as the backend: 3–40 of a–z, 0–9 and "-", not starting or ending with "-". */
export const SLUG = /^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$/

/** "Acme Inc. Status" → "acme-inc-status" (cut to 40 characters). */
export function slugify(title: string): string {
  return title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
    .replace(/-+$/, '')
}

/** Colour of one daily bar. */
export function barColor(uptime: number | null): string {
  if (uptime == null) return 'bg-slate-200'
  if (uptime >= 99.9) return 'bg-emerald-500'
  if (uptime >= 99) return 'bg-lime-400'
  if (uptime >= 95) return 'bg-amber-400'
  return 'bg-red-500'
}

/** The banner at the top of a public status page. Paused monitors don't count. */
export function overall(monitors: PublicMonitor[]): { text: string; style: string } {
  const watched = monitors.filter((m) => m.state !== 'PAUSED')
  if (watched.some((m) => m.state === 'DOWN')) {
    const down = watched.filter((m) => m.state === 'DOWN').length
    return { text: down === watched.length ? 'Major outage' : `${down} of ${watched.length} services down`, style: 'bg-red-600' }
  }
  if (watched.length > 0 && watched.every((m) => m.state === 'UP')) return { text: 'All systems operational', style: 'bg-emerald-600' }
  return { text: 'Status being checked', style: 'bg-slate-500' }
}
