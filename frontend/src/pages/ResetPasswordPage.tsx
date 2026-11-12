import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { api, ApiError } from '../api/client'
import { AuthLayout, CooldownNote } from '../components/AuthLayout'
import { Button, ErrorNote, Field, TextInput } from '../components/ui'
import { useCooldown } from '../lib/useCooldown'

/** Opened from the emailed link: /reset-password?token=… */
export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [repeat, setRepeat] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const cooldown = useCooldown()

  async function submit(event: FormEvent) {
    event.preventDefault()
    const found: Record<string, string> = {}
    if (password.length < 10 || new TextEncoder().encode(password).length > 72) found.password = 'Password must be 10 to 72 characters.'
    else if (password !== repeat) found.repeat = "The passwords don't match."
    setErrors(found)
    setError(undefined)
    if (Object.keys(found).length > 0) return
    setBusy(true)
    try {
      await api('/api/auth/reset-password', { method: 'POST', body: { token, password } })
      navigate('/login', { replace: true, state: { notice: 'Your password was changed and you were logged out everywhere. Log in with the new password.' } })
    } catch (e) {
      if (e instanceof ApiError && e.status === 429 && e.retryAfterSeconds) cooldown.start(e.retryAfterSeconds)
      else if (e instanceof ApiError && e.fieldErrors.password) setErrors({ password: e.fieldErrors.password })
      else setError(e instanceof ApiError ? e.message : 'Could not reach the server. Check your connection.')
    } finally {
      setBusy(false)
    }
  }

  const footer = (
    <Link to="/forgot-password" className="font-medium text-emerald-700 hover:underline">
      Get a new link
    </Link>
  )

  if (!token) {
    return (
      <AuthLayout title="Reset link incomplete" subtitle="Open the link from the email again, or ask for a new one." footer={footer}>
        <p className="text-sm text-slate-600">The address is missing its token.</p>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title="Choose a new password" subtitle="At least 10 characters. Very common passwords are refused." footer={footer}>
      <form onSubmit={submit} noValidate className="space-y-4">
        <CooldownNote seconds={cooldown.remaining} />
        <ErrorNote error={error} />
        <Field label="New password" error={errors.password}>
          {(p) => <TextInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />}
        </Field>
        <Field label="Repeat new password" error={errors.repeat}>
          {(p) => <TextInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} type="password" autoComplete="new-password" value={repeat} onChange={(e) => setRepeat(e.target.value)} />}
        </Field>
        <Button type="submit" disabled={busy || cooldown.remaining > 0} className="w-full">
          {busy ? 'Saving…' : 'Set new password'}
        </Button>
      </form>
    </AuthLayout>
  )
}
