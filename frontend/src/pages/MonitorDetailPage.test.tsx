import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { fakeApi, problem } from '../test/fakeApi'
import { renderApp, signedIn } from '../test/renderApp'

const monitor = {
  id: 'm1', name: 'API', url: 'https://api.example.com/health', method: 'GET', intervalSeconds: 60, timeoutMs: 5000,
  expectedStatus: null, active: true, state: 'UP', uptime24h: 100, lastCheckedAt: '2026-11-12T10:00:00Z', createdAt: '2026-11-12T09:00:00Z',
}
const stats = (window: string, uptime: number) => ({
  body: { window, from: '', to: '', checks: 50, failures: 1, uptime, latencyP50: 120, latencyP95: 480, bucket: window === '30d' ? 'DAY' : 'HOUR', series: [] },
})
const detailRoutes = {
  ...signedIn,
  'GET /api/monitors/m1/stats?window=24h': stats('24h', 98.0),
  'GET /api/monitors/m1/stats?window=7d': stats('7d', 99.5),
  'GET /api/monitors/m1/stats?window=30d': stats('30d', 99.9),
  'GET /api/incidents?monitorId=m1': { body: [{ id: 'i1', monitorId: 'm1', monitorName: 'API', openedAt: '2026-11-12T09:00:00Z', resolvedAt: null, firstErrorType: 'TIMEOUT', summary: null, summaryStatus: 'PENDING', alerts: null }] },
  'GET /api/monitors/m1/checks?size=25': {
    body: { items: [{ checkedAt: '2026-11-12T10:00:00Z', success: false, statusCode: null, latencyMs: 5000, errorType: 'TIMEOUT', errorMessage: 'No answer within 5000 ms.' }], page: 0, size: 25, hasMore: false },
  },
}

describe('monitor detail page', () => {
  it('shows stats per window, incidents and recent checks', async () => {
    fakeApi({ ...detailRoutes, 'GET /api/monitors/m1': { body: monitor } })
    const { user } = renderApp('/monitors/m1')

    expect(await screen.findByRole('heading', { name: 'API' })).toBeInTheDocument()
    expect(await screen.findByText('98.00%')).toBeInTheDocument()
    expect(screen.getByText('480 ms')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: '7 days' }))
    expect(await screen.findByText('99.50%')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '7 days' })).toHaveAttribute('aria-selected', 'true')

    expect(await screen.findByRole('link', { name: /Ongoing/ })).toHaveAttribute('href', '/incidents/i1')
    expect(await screen.findByText('No answer within 5000 ms.')).toBeInTheDocument()
  })

  it('pause asks the server and shows the new state', async () => {
    let active = true
    const { calls } = fakeApi({
      ...detailRoutes,
      'GET /api/monitors/m1': () => ({ body: { ...monitor, active } }),
      'POST /api/monitors/m1/pause': () => {
        active = false
        return { body: { ...monitor, active } }
      },
    })
    const { user } = renderApp('/monitors/m1')

    await user.click(await screen.findByRole('button', { name: 'Pause' }))
    expect(await screen.findByRole('button', { name: 'Resume' })).toBeInTheDocument()
    expect(screen.getByText('Paused')).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/monitors/m1/pause')).toBe(true)
  })

  it('delete only happens after confirming', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    const { calls } = fakeApi({ ...detailRoutes, 'GET /api/monitors/m1': { body: monitor }, 'DELETE /api/monitors/m1': { status: 204 }, 'GET /api/monitors': { body: [] } })
    const { user } = renderApp('/monitors/m1')

    await user.click(await screen.findByRole('button', { name: 'Delete' }))
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText('No monitors yet')).toBeInTheDocument()
    expect(confirm).toHaveBeenCalledTimes(2)
  })

  it('someone else\'s or a deleted monitor shows "not found"', async () => {
    fakeApi({ ...detailRoutes, 'GET /api/monitors/m1': problem(404, 'Monitor not found.') })
    renderApp('/monitors/m1')
    expect(await screen.findByText('Monitor not found')).toBeInTheDocument()
  })
})
