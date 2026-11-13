import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from 'react'
import { Button } from './ui'

export interface ConfirmOptions {
  title: string
  message: ReactNode
  confirmLabel: string
}

/** The dialog itself; use it through useConfirm(). Cancel has the focus, and focus stays inside. */
export function ConfirmDialog({ title, message, confirmLabel, onAnswer }: ConfirmOptions & { onAnswer: (value: boolean) => void }) {
  const titleId = useId()
  const messageId = useId()
  const cancel = useRef<HTMLButtonElement>(null)
  const panel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    cancel.current?.focus()
    return () => previous?.focus()
  }, [])

  function onKeyDown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      event.preventDefault()
      onAnswer(false)
    } else if (event.key === 'Tab') {
      // Keep focus on the two buttons.
      const buttons = panel.current?.querySelectorAll<HTMLButtonElement>('button') ?? []
      const first = buttons[0]
      const last = buttons[buttons.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last?.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first?.focus()
      }
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4" onMouseDown={(e) => e.target === e.currentTarget && onAnswer(false)}>
      <div
        ref={panel}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={messageId}
        onKeyDown={onKeyDown}
        className="w-full max-w-md rounded-xl bg-white p-6 shadow-xl"
      >
        <h2 id={titleId} className="text-lg font-semibold">
          {title}
        </h2>
        <div id={messageId} className="mt-2 text-sm text-slate-600">
          {message}
        </div>
        <div className="mt-6 flex justify-end gap-2">
          <Button ref={cancel} variant="secondary" onClick={() => onAnswer(false)}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={() => onAnswer(true)}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  )
}
