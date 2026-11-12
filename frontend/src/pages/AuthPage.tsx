import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../api/client'
import { useSession } from '../auth/session'
import { Logo } from '../components/Layout'
import { Button, Card, ErrorNote, Field, TextInput } from '../components/ui'

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const { login, register } = useSession()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)
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
      if (e instanceof ApiError) {
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
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex justify-center">
          <Logo />
        </div>
        <Card>
          <h1 className="mb-1 text-xl font-semibold">{isRegister ? 'Create your account' : 'Log in'}</h1>
          <p className="mb-5 text-sm text-slate-600">
            {isRegister ? 'Monitor your sites and get alerted when they go down.' : 'Welcome back.'}
          </p>
          <form onSubmit={submit} noValidate className="space-y-4">
            <ErrorNote error={error} />
            <Field label="Email" error={fieldErrors.email}>
              {(p) => <TextInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />}
            </Field>
            <Field label="Password" error={fieldErrors.password} hint={isRegister ? 'At least 10 characters.' : undefined}>
              {(p) => (
                <TextInput id={p.id} invalid={p.invalid} describedBy={p.describedBy} type="password" autoComplete={isRegister ? 'new-password' : 'current-password'} value={password} onChange={(e) => setPassword(e.target.value)} />
              )}
            </Field>
            <Button type="submit" disabled={busy} className="w-full">
              {busy ? 'Please wait…' : isRegister ? 'Create account' : 'Log in'}
            </Button>
          </form>
        </Card>
        <p className="mt-4 text-center text-sm text-slate-600">
          {isRegister ? 'Already have an account? ' : 'New to Pulse? '}
          <Link to={isRegister ? '/login' : '/register'} className="font-medium text-emerald-700 hover:underline">
            {isRegister ? 'Log in' : 'Create an account'}
          </Link>
        </p>
      </div>
    </div>
  )
}
