import { useCallback, useEffect, useState } from 'react'

/**
 * A live countdown for "Too many requests, try again in N seconds": `start(seconds)` begins it,
 * `remaining` ticks down to 0 (re-rendering about 4 times a second while it runs).
 */
export function useCooldown() {
  const [until, setUntil] = useState<number | null>(null)
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (until === null) return
    const timer = setInterval(() => {
      const t = Date.now()
      setNow(t)
      if (t >= until) setUntil(null)
    }, 250)
    return () => clearInterval(timer)
  }, [until])

  const start = useCallback((seconds: number) => {
    const t = Date.now()
    setNow(t)
    setUntil(t + seconds * 1000)
  }, [])

  const remaining = until === null ? 0 : Math.max(0, Math.ceil((until - now) / 1000))
  return { remaining, start }
}
