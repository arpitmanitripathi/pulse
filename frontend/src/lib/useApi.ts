import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ApiError, publicApi } from '../api/client'

export const REFRESH_MS = 30_000

interface Result<T> {
  data: T | undefined
  error: ApiError | undefined
  loading: boolean
  reload: () => void
}

/**
 * Loads `path` and, if `refreshMs` is set, loads it again on that interval. A newer
 * response always wins: older requests are aborted. `path = null` loads nothing.
 */
export function useApi<T>(path: string | null, { refreshMs, isPublic = false }: { refreshMs?: number; isPublic?: boolean } = {}): Result<T> {
  const [data, setData] = useState<T>()
  const [error, setError] = useState<ApiError>()
  const [loading, setLoading] = useState(path !== null)
  const [version, setVersion] = useState(0)
  const controller = useRef<AbortController | null>(null)

  const reload = useCallback(() => setVersion((v) => v + 1), [])

  useEffect(() => {
    if (path === null) return
    const load = () => {
      controller.current?.abort()
      const current = new AbortController()
      controller.current = current
      const request = isPublic ? publicApi<T>(path, current.signal) : api<T>(path, { signal: current.signal })
      request
        .then((result) => {
          setData(result)
          setError(undefined)
        })
        .catch((e: unknown) => {
          if (current.signal.aborted) return
          setError(e instanceof ApiError ? e : new ApiError(0, 'Could not reach the server. Check your connection.'))
        })
        .finally(() => {
          if (!current.signal.aborted) setLoading(false)
        })
    }
    load()
    const timer = refreshMs ? setInterval(load, refreshMs) : undefined
    return () => {
      clearInterval(timer)
      controller.current?.abort()
    }
  }, [path, refreshMs, isPublic, version])

  return { data, error, loading, reload }
}
