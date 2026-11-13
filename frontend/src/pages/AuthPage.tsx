import { useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router'
import { ApiError } from '../api/client'
import { useSession } from '../auth/session'
import { AuthLayout, CooldownNote, Notice } from '../components/AuthLayout'
import { Button, ErrorNote, Field, PasswordInput, TextInput } from '../components/ui'
import { useCooldown } from '../lib/useCooldown'

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const { login, register } = useSession()
  const location = useLocation()
  const notice = (location.state as { notice?: string } | null)?.notice
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const cooldown = useCooldown()
  const isRegister = mode === 'register'

  async function submit(event: FormEvent) {
    event.preventDefault()
    const errors: Record<string, string> = {}
    if (!email.trim()) errors.email = 'Email is required.'
    if (!password) errors.password = 'Password is required.'
    else if (isRegister && (password.length < 10 || new TextEncoder().encode(password).length > 72)) {
      errors.password = 'Password must be 10 to 72 characters.'
    }
    setFieldErrors(errors)
    setError(undefined)
    if (Object.keys(errors).length > 0) return

    setBusy(true)
    try {
      // On success the session changes and GuestOnly sends the user on.
      await (isRegister ? register(email.trim(), password) : login(email.trim(), password))
    } catch (e) {
      if (e instanceof ApiError && e.status === 429 && e.retryAfterSeconds) {
        cooldown.start(e.retryAfterSeconds)
      } else if (e instanceof ApiError) {
        setFieldErrors(e.fieldErrors)
        setError(Object.keys(e.fieldErrors).length > 0 ? undefined : e.message)
      } else {
        setError('Could not reach the server. Check your connection.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthLayout
      title={isRegister ? 'Create your account' : 'Log in'}
      subtitle={isRegister ? 'Monitor your sites and get alerted when they go down.' : 'Welcome back.'}
      footer={
        <>
          {isRegister ? 'Already have an account? ' : 'New to Pulse? '}
          <Link to={isRegister ? '/login' : '/register'} className="font-medium text-emerald-700 hover:underline">
            {isRegister ? 'Log in' : 'Create an account'}
          </Link>
        </>
      }
    >
      <form onSubmit={submit} noValidate className="space-y-4">
        {notice && <Notice>{notice}</Notice>}
        <CooldownNote seconds={cooldown.remaining} />
        <ErrorNote error={error} />
        <Field label="Email" error={fieldErrors.email}>
          {(p) => <TextInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />}
        </Field>
        <Field label="Password" error={fieldErrors.password} hint={isRegister ? 'At least 10 characters. Very common passwords are refused.' : undefined}>
          {(p) => (
            <PasswordInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} autoComplete={isRegister ? 'new-password' : 'current-password'} value={password} onChange={(e) => setPassword(e.target.value)} />
          )}
        </Field>
        {!isRegister && (
          <p className="-mt-2 text-right text-sm">
            <Link to="/forgot-password" className="text-emerald-700 hover:underline">
              Forgot password?
            </Link>
          </p>
        )}
        <Button type="submit" disabled={busy || cooldown.remaining > 0} className="w-full">
          {busy ? 'Please wait…' : isRegister ? 'Create account' : 'Log in'}
        </Button>
      </form>
    </AuthLayout>
  )
}
