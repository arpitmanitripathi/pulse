import { useState, type FormEvent } from 'react'
import { api, ApiError } from '../api/client'
import type { AlertChannel, ChannelType } from '../api/types'
import { Button, Card, Empty, ErrorNote, Field, Loading, PageTitle, Select, TextInput } from '../components/ui'
import { useApi } from '../lib/useApi'

export const MAX_CHANNELS = 5

const TYPES: Record<ChannelType, { label: string; target: string; placeholder: string; hint: string }> = {
  EMAIL: { label: 'Email', target: 'Email address', placeholder: 'you@example.com', hint: '' },
  SLACK: {
    label: 'Slack',
    target: 'Webhook URL',
    placeholder: 'https://hooks.slack.com/services/…',
    hint: 'In Slack: Apps → Incoming Webhooks → Add to a channel. Keep this URL secret.',
  },
  DISCORD: {
    label: 'Discord',
    target: 'Webhook URL',
    placeholder: 'https://discord.com/api/webhooks/…',
    hint: 'In Discord: channel settings → Integrations → Webhooks → New Webhook → Copy URL.',
  },
}

export function AlertsPage() {
  const channels = useApi<AlertChannel[]>('/api/alert-channels')
  const [adding, setAdding] = useState(false)
  const count = channels.data?.length ?? 0

  return (
    <div className="max-w-3xl">
      <PageTitle actions={!adding && count < MAX_CHANNELS && channels.data ? <Button onClick={() => setAdding(true)}>Add channel</Button> : undefined}>Alerts</PageTitle>
      <p className="-mt-3 mb-6 text-sm text-slate-600">
        When a monitor goes down, every channel gets one DOWN message, and one RECOVERED message when it comes back. Up to {MAX_CHANNELS} channels.
      </p>
      {adding && (
        <Card className="mb-6">
          <ChannelForm
            onDone={() => {
              setAdding(false)
              channels.reload()
            }}
            onCancel={() => setAdding(false)}
          />
        </Card>
      )}
      <ErrorNote error={channels.error} />
      {channels.loading && !channels.data && <Loading />}
      {channels.data && channels.data.length === 0 && !adding && (
        <Empty>
          <p className="mb-2 font-medium text-slate-800">No alert channels</p>
          <p>Incidents are still recorded, but nobody is told. Add an email address, Slack or Discord.</p>
        </Empty>
      )}
      {channels.data && channels.data.length > 0 && (
        <ul className="space-y-3">
          {channels.data.map((c) => (
            <ChannelRow key={c.id} channel={c} onDeleted={channels.reload} />
          ))}
        </ul>
      )}
    </div>
  )
}

function ChannelRow({ channel, onDeleted }: { channel: AlertChannel; onDeleted: () => void }) {
  const [result, setResult] = useState<{ ok: boolean; text: string }>()
  const [busy, setBusy] = useState(false)

  async function test() {
    setBusy(true)
    setResult(undefined)
    try {
      await api(`/api/alert-channels/${channel.id}/test`, { method: 'POST' })
      setResult({ ok: true, text: 'Test alert sent. Check that it arrived.' })
    } catch (e) {
      setResult({ ok: false, text: e instanceof ApiError ? e.message : 'Could not reach the server.' })
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    if (!window.confirm(`Delete the channel "${channel.name}"?`)) return
    setBusy(true)
    try {
      await api(`/api/alert-channels/${channel.id}`, { method: 'DELETE' })
      onDeleted()
    } catch (e) {
      setResult({ ok: false, text: e instanceof ApiError ? e.message : 'Could not reach the server.' })
      setBusy(false)
    }
  }

  return (
    <li className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="font-medium">
            {channel.name} <span className="ml-1 rounded bg-slate-100 px-1.5 py-0.5 text-xs font-normal text-slate-600">{TYPES[channel.type].label}</span>
          </p>
          <p className="truncate text-sm text-slate-500">{channel.target}</p>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" disabled={busy} onClick={() => void test()}>
            {busy ? 'Sending…' : 'Send test'}
          </Button>
          <Button variant="danger" disabled={busy} onClick={() => void remove()}>
            Delete
          </Button>
        </div>
      </div>
      {result && (
        <p role="status" className={`mt-3 text-sm ${result.ok ? 'text-emerald-700' : 'text-red-700'}`}>
          {result.text}
        </p>
      )}
    </li>
  )
}

function ChannelForm({ onDone, onCancel }: { onDone: () => void; onCancel: () => void }) {
  const [type, setType] = useState<ChannelType>('EMAIL')
  const [name, setName] = useState('')
  const [target, setTarget] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const info = TYPES[type]

  async function submit(event: FormEvent) {
    event.preventDefault()
    const found: Record<string, string> = {}
    if (!name.trim()) found.name = 'Name is required.'
    if (!target.trim()) found.target = `${info.target} is required.`
    setErrors(found)
    setError(undefined)
    if (Object.keys(found).length > 0) return
    setBusy(true)
    try {
      await api('/api/alert-channels', { method: 'POST', body: { type, name: name.trim(), target: target.trim() } })
      onDone()
    } catch (e) {
      if (e instanceof ApiError && Object.keys(e.fieldErrors).length > 0) setErrors(e.fieldErrors)
      else setError(e instanceof ApiError ? e.message : 'Could not reach the server.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <h2 className="font-semibold">New alert channel</h2>
      <ErrorNote error={error} />
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Type" error={errors.type}>
          {(p) => (
            <Select id={p.id} value={type} onChange={(e) => setType(e.target.value as ChannelType)}>
              {(Object.keys(TYPES) as ChannelType[]).map((t) => (
                <option key={t} value={t}>
                  {TYPES[t].label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Name" error={errors.name}>
          {(p) => <TextInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} value={name} onChange={(e) => setName(e.target.value)} placeholder="On-call email" maxLength={100} />}
        </Field>
      </div>
      <Field label={info.target} error={errors.target} hint={info.hint || undefined}>
        {(p) => <TextInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} value={target} onChange={(e) => setTarget(e.target.value)} placeholder={info.placeholder} type={type === 'EMAIL' ? 'email' : 'url'} />}
      </Field>
      <div className="flex gap-2">
        <Button type="submit" disabled={busy}>
          {busy ? 'Saving…' : 'Add channel'}
        </Button>
        <Button variant="secondary" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  )
}
