import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from 'react'
import type { ApiError } from '../api/client'

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <section className={`rounded-xl border border-slate-200 bg-white p-5 shadow-sm ${className}`}>{children}</section>
}

export function PageTitle({ children, actions }: { children: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
      <h1 className="text-2xl font-semibold tracking-tight">{children}</h1>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  )
}

type Variant = 'primary' | 'secondary' | 'danger'

const variants: Record<Variant, string> = {
  primary: 'bg-emerald-600 text-white hover:bg-emerald-700 disabled:bg-emerald-300',
  secondary: 'border border-slate-300 bg-white text-slate-700 hover:bg-slate-50 disabled:text-slate-400',
  danger: 'border border-red-200 bg-white text-red-700 hover:bg-red-50 disabled:text-red-300',
}

export function Button({ variant = 'primary', className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      type="button"
      className={`inline-flex items-center justify-center rounded-lg px-3.5 py-2 text-sm font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 disabled:cursor-not-allowed ${variants[variant]} ${className}`}
      {...props}
    />
  )
}

const inputClass =
  'block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm shadow-xs focus:border-emerald-500 focus:outline-none focus:ring-2 focus:ring-emerald-500/30 aria-invalid:border-red-400'

/** A labelled form field with its error message underneath. */
export function Field({ label, error, hint, children }: { label: string; error?: string; hint?: string; children: (props: { id: string; invalid: boolean; describedBy?: string }) => ReactNode }) {
  const id = `field-${label.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-sm font-medium text-slate-700">
        {label}
      </label>
      {children({ id, invalid: Boolean(error), describedBy })}
      {error ? (
        <p id={`${id}-error`} className="text-sm text-red-600">
          {error}
        </p>
      ) : (
        hint && (
          <p id={`${id}-hint`} className="text-xs text-slate-500">
            {hint}
          </p>
        )
      )}
    </div>
  )
}

export function TextInput({ invalid, describedBy, ...props }: InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean; describedBy?: string }) {
  return <input className={inputClass} aria-invalid={invalid || undefined} aria-describedby={describedBy} {...props} />
}

export function Select({ invalid, describedBy, ...props }: SelectHTMLAttributes<HTMLSelectElement> & { invalid?: boolean; describedBy?: string }) {
  return <select className={inputClass} aria-invalid={invalid || undefined} aria-describedby={describedBy} {...props} />
}

export function ErrorNote({ error }: { error: ApiError | string | undefined | null }) {
  if (!error) return null
  return (
    <div role="alert" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
      {typeof error === 'string' ? error : error.message}
    </div>
  )
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <p role="status" className="py-10 text-center text-sm text-slate-500">
      {label}
    </p>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center text-sm text-slate-600">{children}</div>
}

export function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div>
      <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="mt-1 text-xl font-semibold tabular-nums">{value}</dd>
    </div>
  )
}
