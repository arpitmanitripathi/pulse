// Shapes returned by the backend (see /swagger-ui). Times are ISO-8601 strings in UTC.

export type MonitorState = 'UP' | 'DOWN' | 'UNKNOWN'
export type ErrorType = 'NONE' | 'TIMEOUT' | 'DNS' | 'TLS' | 'CONNECTION' | 'STATUS' | 'BLOCKED'

export interface User {
  id: string
  email: string
}

export interface Monitor {
  id: string
  name: string
  url: string
  method: 'GET' | 'HEAD'
  intervalSeconds: number
  timeoutMs: number
  expectedStatus: number | null
  active: boolean
  state: MonitorState
  uptime24h: number | null
  lastCheckedAt: string | null
  createdAt: string
}

export interface MonitorInput {
  name: string
  url: string
  method: 'GET' | 'HEAD'
  intervalSeconds: number
  timeoutMs: number
  expectedStatus: number | null
}

export interface CheckResult {
  checkedAt: string
  success: boolean
  statusCode: number | null
  latencyMs: number
  errorType: ErrorType
  errorMessage: string | null
}

export interface CheckPage {
  items: CheckResult[]
  page: number
  size: number
  hasMore: boolean
}

export type StatsWindow = '24h' | '7d' | '30d'

export interface StatsPoint {
  start: string
  checks: number
  failures: number
  uptime: number | null
  latencyP50: number | null
  latencyP95: number | null
}

export interface MonitorStats {
  window: StatsWindow
  from: string
  to: string
  checks: number
  failures: number
  uptime: number | null
  latencyP50: number | null
  latencyP95: number | null
  bucket: 'HOUR' | 'DAY'
  series: StatsPoint[]
}

export type Resolution = 'RECOVERED' | 'MONITOR_CHANGED'

export type SummaryStatus = 'PENDING' | 'DONE' | 'FAILED' | 'SKIPPED'

export interface Delivery {
  channelId: string
  channelName: string
  type: ChannelType
  kind: 'DOWN' | 'RECOVERED'
  attempts: number
  sentAt: string | null
  lastError: string | null
}

export interface Incident {
  id: string
  monitorId: string
  monitorName: string
  openedAt: string
  resolvedAt: string | null
  /** Why it closed: it recovered, or the monitor was paused, resumed or got a new URL. Null while open. */
  resolution: Resolution | null
  firstErrorType: ErrorType
  summary: string | null
  summaryStatus: SummaryStatus
  alerts: Delivery[] | null
}

export type ChannelType = 'EMAIL' | 'SLACK' | 'DISCORD'

export interface AlertChannel {
  id: string
  type: ChannelType
  name: string
  target: string
  createdAt: string
}

export interface StatusPage {
  id: string
  slug: string
  title: string
  monitorIds: string[]
  createdAt: string
}

export interface PublicStatus {
  title: string
  monitors: PublicMonitor[]
  incidents: { monitorName: string; openedAt: string; resolvedAt: string | null; resolution: Resolution | null }[]
  generatedAt: string
}

export interface PublicMonitor {
  name: string
  state: MonitorState | 'PAUSED'
  uptime24h: number | null
  uptime7d: number | null
  uptime30d: number | null
  days: { date: string; uptime: number | null }[]
  latency24h: { hour: string; latencyP50: number | null; latencyP95: number | null }[]
}
