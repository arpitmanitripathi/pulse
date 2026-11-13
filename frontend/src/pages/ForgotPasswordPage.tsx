import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { api, ApiError } from '../api/client'
import { AuthLayout, CooldownNote, Notice } from '../components/AuthLayout'
import { Button, ErrorNote, Field, TextInput } from '../components/ui'
import { useCooldown } from '../lib/useCooldown'

/** Asks for a reset link. The answer is the same whether or not the email has an account. */
export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [sentTo, setSentTo] = useState<string>()
  const [fieldError, setFieldError] = useState<string>()
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const cooldown = useCooldown()

  async function submit(event: FormEvent) {
    event.preventDefault()
    setError(undefined)
    if (!email.trim()) {
      setFieldError('Email is required.')
      return
    }
    setFieldError(undefined)
    setBusy(true)
    try {
      await api('/api/auth/forgot-password', { method: 'POST', body: { email: email.trim() } })
      setSentTo(email.trim())
    } catch (e) {
      if (e instanceof ApiError && e.status === 429 && e.retryAfterSeconds) cooldown.start(e.retryAfterSeconds)
      else if (e instanceof ApiError && e.fieldErrors.email) setFieldError(e.fieldErrors.email)
      else setError(e instanceof ApiError ? e.message : 'Could not reach the server. Check your connection.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      title="Reset your password"
      subtitle="Enter the email you signed up with. We'll send you a link to choose a new password."
      footer={
        <Link to="/login" className="font-medium text-emerald-700 hover:underline">
          Back to log in
        </Link>
      }
    >
      {sentTo ? (
        <Notice>
          If an account exists for <strong>{sentTo}</strong>, we've sent a link to it. The link works once, for 30 minutes. Check your spam folder if it doesn't arrive.
        </Notice>
      ) : (
        <form onSubmit={submit} noValidate className="space-y-4">
          <CooldownNote seconds={cooldown.remaining} />
          <ErrorNote error={error} />
          <Field label="Email" error={fieldError}>
            {(p) => <TextInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />}
          </Field>
          <Button type="submit" disabled={busy || cooldown.remaining > 0} className="w-full">
            {busy ? 'Please wait…' : 'Send reset link'}
          </Button>
        </form>
      )}
    </AuthLayout>
  )
}
