import { useCallback, useRef, useState, type ReactNode } from 'react'
import { ConfirmDialog, type ConfirmOptions } from '../components/ConfirmDialog'

/**
 * A Pulse-styled replacement for window.confirm().
 * `const [confirm, dialog] = useConfirm()`; render `{dialog}`; `if (await confirm({...})) …`
 *
 * Safe by default: Cancel has the focus, so Enter or Escape never deletes anything by accident.
 * Focus stays inside the dialog while it is open and goes back to where it was afterwards.
 */
export function useConfirm(): [(options: ConfirmOptions) => Promise<boolean>, ReactNode] {
  const [options, setOptions] = useState<ConfirmOptions | null>(null)
  const resolver = useRef<((answer: boolean) => void) | null>(null)

  const confirm = useCallback((next: ConfirmOptions) => {
    resolver.current?.(false) // a dialog that was still open counts as cancelled
    setOptions(next)
    return new Promise<boolean>((resolve) => {
      resolver.current = resolve
    })
  }, [])

  const answer = useCallback((value: boolean) => {
    resolver.current?.(value)
    resolver.current = null
    setOptions(null)
  }, [])

  return [confirm, options ? <ConfirmDialog {...options} onAnswer={answer} /> : null]
}
