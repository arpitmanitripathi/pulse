export const INTERVALS = [30, 60, 120, 300]

/** Same limits and messages as the backend (docs/DESIGN.md, "Fixed numbers"). */
export function validateMonitor(v: { name: string; url: string; timeoutMs: string; expectedStatus: string }): Record<string, string> {
  const errors: Record<string, string> = {}
  const name = v.name.trim()
  if (!name) errors.name = 'Name is required.'
  else if (name.length > 100) errors.name = 'Name must be 1 to 100 characters.'

  const url = v.url.trim()
  if (!url) errors.url = 'URL is required.'
  else if (url.length > 2048) errors.url = 'URL must be at most 2048 characters.'
  else if (!/^https?:\/\/[^\s/?#]+/i.test(url)) errors.url = 'Enter a full URL starting with http:// or https://'

  const timeout = Number(v.timeoutMs)
  if (!Number.isInteger(timeout) || timeout < 1000 || timeout > 10000) errors.timeoutMs = 'Timeout must be between 1000 and 10000 ms.'

  if (v.expectedStatus.trim()) {
    const status = Number(v.expectedStatus)
    if (!Number.isInteger(status) || status < 100 || status > 599) errors.expectedStatus = 'Expected status must be between 100 and 599.'
  }
  return errors
}
