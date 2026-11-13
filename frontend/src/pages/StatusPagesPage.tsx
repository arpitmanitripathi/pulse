import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api, ApiError } from '../api/client'
import type { Monitor, StatusPage } from '../api/types'
import { Button, Card, Empty, ErrorNote, Field, Loading, PageTitle, TextInput } from '../components/ui'
import { SLUG, slugify } from '../lib/status'
import { useApi } from '../lib/useApi'
import { useConfirm } from '../lib/useConfirm'

export const MAX_PAGES = 3

export function StatusPagesPage() {
  const pages = useApi<StatusPage[]>('/api/status-pages')
  const monitors = useApi<Monitor[]>('/api/monitors')
  const [editing, setEditing] = useState<StatusPage | 'new' | null>(null)
  const count = pages.data?.length ?? 0

  if (!pages.data || !monitors.data) {
    return pages.error || monitors.error ? <ErrorNote error={pages.error ?? monitors.error} /> : <Loading />
  }
  const names = new Map(monitors.data.map((m) => [m.id, m.name]))

  return (
    <div className="max-w-3xl">
      <PageTitle actions={editing === null && count < MAX_PAGES ? <Button onClick={() => setEditing('new')}>New status page</Button> : undefined}>Status pages</PageTitle>
      <p className="-mt-3 mb-6 text-sm text-slate-600">
        A public page anyone can open, showing uptime and incidents for the monitors you pick. It never shows URLs or error details. Up to {MAX_PAGES} pages.
      </p>
      {editing !== null && (
        <Card className="mb-6">
          <StatusPageForm
            page={editing === 'new' ? undefined : editing}
            monitors={monitors.data}
            onDone={() => {
              setEditing(null)
              pages.reload()
            }}
            onCancel={() => setEditing(null)}
          />
        </Card>
      )}
      {pages.data.length === 0 && editing === null && (
        <Empty>
          <p className="mb-2 font-medium text-slate-800">No status pages yet</p>
          <p>Create one to share your uptime with your users.</p>
        </Empty>
      )}
      <ul className="space-y-3">
        {pages.data.map((p) => (
          <li key={p.id} className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="font-medium">{p.title}</p>
                <Link to={`/s/${p.slug}`} target="_blank" rel="noopener" className="text-sm text-emerald-700 hover:underline">
                  {window.location.origin}/s/{p.slug} ↗
                </Link>
                <p className="mt-1 text-xs text-slate-500">{p.monitorIds.length === 0 ? 'No monitors' : p.monitorIds.map((id) => names.get(id) ?? 'Deleted monitor').join(' · ')}</p>
              </div>
              {editing === null && (
                <div className="flex gap-2">
                  <Button variant="secondary" onClick={() => setEditing(p)}>
                    Edit
                  </Button>
                  <DeleteButton page={p} onDeleted={pages.reload} />
                </div>
              )}
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}

function DeleteButton({ page, onDeleted }: { page: StatusPage; onDeleted: () => void }) {
  const [busy, setBusy] = useState(false)
  const [confirm, confirmDialog] = useConfirm()
  return (
    <>
    {confirmDialog}
    <Button
      variant="danger"
      disabled={busy}
      onClick={async () => {
        const ok = await confirm({
          title: `Delete "${page.title}"?`,
          message: `The public link /s/${page.slug} stops working for everyone who has it.`,
          confirmLabel: 'Delete page',
        })
        if (!ok) return
        setBusy(true)
        try {
          await api(`/api/status-pages/${page.id}`, { method: 'DELETE' })
          onDeleted()
        } catch {
          setBusy(false)
        }
      }}
    >
      Delete
    </Button>
    </>
  )
}

function StatusPageForm({ page, monitors, onDone, onCancel }: { page?: StatusPage; monitors: Monitor[]; onDone: () => void; onCancel: () => void }) {
  const [title, setTitle] = useState(page?.title ?? '')
  const [slug, setSlug] = useState(page?.slug ?? '')
  const [slugTouched, setSlugTouched] = useState(Boolean(page))
  const [selected, setSelected] = useState<string[]>(page?.monitorIds.filter((id) => monitors.some((m) => m.id === id)) ?? [])
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const byId = new Map(monitors.map((m) => [m.id, m]))

  function toggle(id: string) {
    setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]))
  }

  function move(index: number, by: -1 | 1) {
    setSelected((s) => {
      const next = [...s]
      const [item] = next.splice(index, 1)
      next.splice(index + by, 0, item!)
      return next
    })
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    const found: Record<string, string> = {}
    if (!title.trim()) found.title = 'Title is required.'
    else if (title.trim().length > 100) found.title = 'Title must be 1 to 100 characters.'
    if (!SLUG.test(slug)) found.slug = 'Address must be 3 to 40 lower-case letters, digits or hyphens, and cannot start or end with a hyphen.'
    setErrors(found)
    setError(undefined)
    if (Object.keys(found).length > 0) return
    setBusy(true)
    try {
      const body = { title: title.trim(), slug, monitorIds: selected }
      await api(page ? `/api/status-pages/${page.id}` : '/api/status-pages', { method: page ? 'PATCH' : 'POST', body })
      onDone()
    } catch (e) {
      if (e instanceof ApiError && Object.keys(e.fieldErrors).length > 0) setErrors(e.fieldErrors)
      else if (e instanceof ApiError && e.status === 409 && e.message.includes('address')) setErrors({ slug: e.message })
      else setError(e instanceof ApiError ? e.message : 'Could not reach the server.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <h2 className="font-semibold">{page ? 'Edit status page' : 'New status page'}</h2>
      <ErrorNote error={error} />
      <Field label="Title" error={errors.title}>
        {(p) => (
          <TextInput
            id={p.id}
            invalid={p.invalid}
            describedBy={p.describedBy}
            value={title}
            maxLength={100}
            placeholder="Acme status"
            onChange={(e) => {
              setTitle(e.target.value)
              if (!slugTouched) setSlug(slugify(e.target.value))
            }}
          />
        )}
      </Field>
      <Field label="Address" error={errors.slug} hint={`${window.location.origin}/s/${slug || '…'}`}>
        {(p) => (
          <TextInput
            id={p.id}
            invalid={p.invalid}
            describedBy={p.describedBy}
            value={slug}
            maxLength={40}
            placeholder="acme"
            onChange={(e) => {
              setSlugTouched(true)
              setSlug(e.target.value.toLowerCase())
            }}
          />
        )}
      </Field>
      <fieldset>
        <legend className="mb-2 text-sm font-medium text-slate-700">Monitors to show {errors.monitorIds && <span className="font-normal text-red-600">— {errors.monitorIds}</span>}</legend>
        {monitors.length === 0 ? (
          <p className="text-sm text-slate-500">You have no monitors yet.</p>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2">
            <ul className="space-y-1.5">
              {monitors.map((m) => (
                <li key={m.id}>
                  <label className="flex items-center gap-2 text-sm">
                    <input type="checkbox" checked={selected.includes(m.id)} onChange={() => toggle(m.id)} className="h-4 w-4 accent-emerald-600" />
                    {m.name}
                  </label>
                </li>
              ))}
            </ul>
            {selected.length > 0 && (
              <div>
                <p className="mb-1.5 text-xs font-medium uppercase tracking-wide text-slate-500">Order on the page</p>
                <ol className="space-y-1">
                  {selected.map((id, i) => (
                    <li key={id} className="flex items-center justify-between rounded-md bg-slate-50 px-2 py-1 text-sm">
                      <span>
                        {i + 1}. {byId.get(id)?.name}
                      </span>
                      <span className="flex gap-1">
                        <button type="button" aria-label={`Move ${byId.get(id)?.name} up`} disabled={i === 0} onClick={() => move(i, -1)} className="px-1 text-slate-500 hover:text-slate-900 disabled:opacity-30">
                          ↑
                        </button>
                        <button type="button" aria-label={`Move ${byId.get(id)?.name} down`} disabled={i === selected.length - 1} onClick={() => move(i, 1)} className="px-1 text-slate-500 hover:text-slate-900 disabled:opacity-30">
                          ↓
                        </button>
                      </span>
                    </li>
                  ))}
                </ol>
              </div>
            )}
          </div>
        )}
      </fieldset>
      <div className="flex gap-2">
        <Button type="submit" disabled={busy}>
          {busy ? 'Saving…' : page ? 'Save changes' : 'Create page'}
        </Button>
        <Button variant="secondary" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  )
}
