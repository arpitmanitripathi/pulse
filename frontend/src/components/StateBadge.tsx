import type { MonitorState } from '../api/types'

const styles: Record<MonitorState | 'PAUSED', { label: string; dot: string; text: string }> = {
  UP: { label: 'Up', dot: 'bg-emerald-500', text: 'text-emerald-800 bg-emerald-50 ring-emerald-200' },
  DOWN: { label: 'Down', dot: 'bg-red-500', text: 'text-red-800 bg-red-50 ring-red-200' },
  UNKNOWN: { label: 'Pending', dot: 'bg-slate-400', text: 'text-slate-700 bg-slate-100 ring-slate-200' },
  PAUSED: { label: 'Paused', dot: 'bg-amber-400', text: 'text-amber-800 bg-amber-50 ring-amber-200' },
}

/** State with a coloured dot and a word, so it doesn't rely on colour alone. */
export function StateBadge({ state, active = true }: { state: MonitorState | 'PAUSED'; active?: boolean }) {
  const style = styles[active ? state : 'PAUSED']
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${style.text}`}>
      <span aria-hidden className={`h-1.5 w-1.5 rounded-full ${style.dot}`} />
      {style.label}
    </span>
  )
}
