// Display helpers. Times are shown in the browser's own time zone.

export function formatUptime(uptime: number | null | undefined): string {
  return uptime == null ? '—' : `${uptime.toFixed(2)}%`
}

export function formatMs(ms: number | null | undefined): string {
  if (ms == null) return '—'
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)} s` : `${ms} ms`
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

export function formatHour(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

export function formatDay(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

/** "3 min", "2 h 5 min", "1 d 4 h". */
export function formatDuration(fromIso: string, toIso: string | null, now = Date.now()): string {
  const end = toIso ? new Date(toIso).getTime() : now
  let minutes = Math.max(0, Math.round((end - new Date(fromIso).getTime()) / 60_000))
  if (minutes < 1) return 'under 1 min'
  const days = Math.floor(minutes / 1440)
  minutes -= days * 1440
  const hours = Math.floor(minutes / 60)
  minutes -= hours * 60
  if (days > 0) return hours > 0 ? `${days} d ${hours} h` : `${days} d`
  if (hours > 0) return minutes > 0 ? `${hours} h ${minutes} min` : `${hours} h`
  return `${minutes} min`
}

export function formatInterval(seconds: number): string {
  return seconds < 60 ? `${seconds} s` : `${seconds / 60} min`
}

/** "Ongoing", "Resolved" (it recovered) or "Closed" (monitoring was paused or changed first). */
export function incidentStatus(i: { resolvedAt: string | null; resolution: string | null }): 'Ongoing' | 'Resolved' | 'Closed' {
  if (!i.resolvedAt) return 'Ongoing'
  return i.resolution === 'MONITOR_CHANGED' ? 'Closed' : 'Resolved'
}

export const ERROR_LABELS: Record<string, string> = {
  NONE: 'OK',
  TIMEOUT: 'Timeout',
  DNS: 'DNS failure',
  TLS: 'HTTPS/TLS error',
  CONNECTION: 'Connection failed',
  STATUS: 'Bad status code',
  BLOCKED: 'Blocked address',
}
