import { useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import type { Monitor, MonitorInput } from '../api/types'
import { INTERVALS, validateMonitor } from '../lib/monitorRules'
import { Button, ErrorNote, Field, Select, TextInput } from './ui'

export function MonitorForm({ initial, submitLabel, onSubmit, onCancel }: { initial?: Monitor; submitLabel: string; onSubmit: (input: MonitorInput) => Promise<void>; onCancel?: () => void }) {
  const [name, setName] = useState(initial?.name ?? '')
  const [url, setUrl] = useState(initial?.url ?? 'https://')
  const [method, setMethod] = useState<'GET' | 'HEAD'>(initial?.method ?? 'GET')
  const [interval, setIntervalSeconds] = useState(initial?.intervalSeconds ?? 60)
  const [timeoutMs, setTimeoutMs] = useState(String(initial?.timeoutMs ?? 5000))
  const [expectedStatus, setExpectedStatus] = useState(initial?.expectedStatus == null ? '' : String(initial.expectedStatus))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const found = validateMonitor({ name, url, timeoutMs, expectedStatus })
    setErrors(found)
    setError(undefined)
    if (Object.keys(found).length > 0) return
    setBusy(true)
    try {
      await onSubmit({
        name: name.trim(),
        url: url.trim(),
        method,
        intervalSeconds: interval,
        timeoutMs: Number(timeoutMs),
        expectedStatus: expectedStatus.trim() ? Number(expectedStatus) : null,
      })
    } catch (e) {
      if (e instanceof ApiError && Object.keys(e.fieldErrors).length > 0) setErrors(e.fieldErrors)
      else setError(e instanceof ApiError ? e.message : 'Could not reach the server. Check your connection.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-5">
      <ErrorNote error={error} />
      <Field label="Name" error={errors.name} hint="Shown in alerts and on status pages.">
        {(p) => <TextInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} value={name} onChange={(e) => setName(e.target.value)} placeholder="My API" maxLength={100} />}
      </Field>
      <Field label="URL" error={errors.url} hint="Must be publicly reachable. Private and internal addresses are refused.">
        {(p) => <TextInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} value={url} onChange={(e) => setUrl(e.target.value)} type="url" inputMode="url" />}
      </Field>
      <div className="grid gap-5 sm:grid-cols-2">
        <Field label="Check every" error={errors.intervalSeconds}>
          {(p) => (
            <Select id={p.id} invalid={p.invalid} value={interval} onChange={(e) => setIntervalSeconds(Number(e.target.value))}>
              {INTERVALS.map((s) => (
                <option key={s} value={s}>
                  {s < 60 ? `${s} seconds` : `${s / 60} minute${s > 60 ? 's' : ''}`}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Method" error={errors.method} hint="HEAD skips the page body; some servers don't support it.">
          {(p) => (
            <Select id={p.id} invalid={p.invalid} describedBy={p.describedBy} value={method} onChange={(e) => setMethod(e.target.value as 'GET' | 'HEAD')}>
              <option value="GET">GET</option>
              <option value="HEAD">HEAD</option>
            </Select>
          )}
        </Field>
        <Field label="Timeout (ms)" error={errors.timeoutMs} hint="1000 to 10000.">
          {(p) => <TextInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} value={timeoutMs} onChange={(e) => setTimeoutMs(e.target.value)} inputMode="numeric" />}
        </Field>
        <Field label="Expected status (optional)" error={errors.expectedStatus} hint="Empty means any 2xx counts as up.">
          {(p) => <TextInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} value={expectedStatus} onChange={(e) => setExpectedStatus(e.target.value)} inputMode="numeric" placeholder="200" />}
        </Field>
      </div>
      <div className="flex gap-2">
        <Button type="submit" disabled={busy}>
          {busy ? 'Saving…' : submitLabel}
        </Button>
        {onCancel && (
          <Button variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  )
}
