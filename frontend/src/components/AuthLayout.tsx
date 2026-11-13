import type { ReactNode } from 'react'
import { Logo } from './Layout'
import { Card } from './ui'

/** The centred card used by login, register, forgot password and reset password. */
export function AuthLayout({ title, subtitle, children, footer }: { title: string; subtitle: string; children: ReactNode; footer?: ReactNode }) {
  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex justify-center">
          <Logo />
        </div>
        <Card>
          <h1 className="mb-1 text-xl font-semibold">{title}</h1>
          <p className="mb-5 text-sm text-slate-600">{subtitle}</p>
          {children}
        </Card>
        {footer && <p className="mt-4 text-center text-sm text-slate-600">{footer}</p>}
      </div>
    </div>
  )
}

export function Notice({ children }: { children: ReactNode }) {
  return (
    <div role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800">
      {children}
    </div>
  )
}

/** The message for a 429, counting down. */
export function CooldownNote({ seconds }: { seconds: number }) {
  if (seconds <= 0) return null
  return (
    <div role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
      Too many attempts from this network. Try again in {seconds} s.
    </div>
  )
}
