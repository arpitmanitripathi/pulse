import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Monitor } from '../api/types'
import { fakeApi, problem } from '../test/fakeApi'
import { renderApp, signedIn } from '../test/renderApp'

function monitor(id: string, over: Partial<Monitor> = {}): Monitor {
  return {
    id, name: `Monitor ${id}`, url: `https://${id}.example.com/`, method: 'GET', intervalSeconds: 60, timeoutMs: 5000,
    expectedStatus: null, active: true, state: 'UP', uptime24h: 99.95, lastCheckedAt: null, createdAt: '2026-11-12T10:00:00Z', ...over,
  }
}

const stats = (p50s: (number | null)[]) => ({
  body: { window: '24h', checks: 10, failures: 0, uptime: 100, latencyP50: 100, latencyP95: 200, bucket: 'HOUR', from: '', to: '',
    series: p50s.map((v, i) => ({ start: `2026-11-12T${String(i).padStart(2, '0')}:00:00Z`, checks: 1, failures: 0, uptime: 100, latencyP50: v, latencyP95: v })) },
})

describe('monitor list', () => {
  it('shows each monitor with state, 24 h uptime and a sparkline', async () => {
    fakeApi({
      ...signedIn,
      'GET /api/monitors': { body: [monitor('a', { name: 'API', state: 'DOWN', uptime24h: 87.5 }), monitor('b', { name: 'Blog', active: false, uptime24h: null })] },
      'GET /api/monitors/a/stats?window=24h': stats([120, 140, null, 400]),
      'GET /api/monitors/b/stats?window=24h': stats([null, null]),
    })
    renderApp('/monitors')

    const api = (await screen.findByRole('link', { name: 'API' })).closest('tr')!
    expect(within(api).getByText('Down')).toBeInTheDocument()
    expect(within(api).getByText('87.50%')).toBeInTheDocument()
    expect(await within(api).findByRole('img', { name: 'Response time, 120 ms to 400 ms' })).toBeInTheDocument()
    expect(api).toHaveTextContent('https://a.example.com/')

    const blog = screen.getByRole('link', { name: 'Blog' }).closest('tr')!
    expect(within(blog).getByText('Paused')).toBeInTheDocument()
    expect(within(blog).getByText('—')).toBeInTheDocument()
    expect(await within(blog).findByText('no data yet')).toBeInTheDocument()
  })

  it('a new monitor with one hour of data shows that value instead of a line', async () => {
    fakeApi({ ...signedIn, 'GET /api/monitors': { body: [monitor('a', { name: 'New' })] }, 'GET /api/monitors/a/stats?window=24h': stats([null, 133]) })
    renderApp('/monitors')
    expect(await screen.findByText('133 ms')).toBeInTheDocument()
  })

  it('hides "Add monitor" at the limit of 20', async () => {
    const routes: Record<string, { body: unknown }> = { 'GET /api/monitors': { body: Array.from({ length: 20 }, (_, i) => monitor(String(i))) } }
    for (let i = 0; i < 20; i++) routes[`GET /api/monitors/${i}/stats?window=24h`] = stats([])
    fakeApi({ ...signedIn, ...routes })
    renderApp('/monitors')

    expect(await screen.findByText('20 of 20 monitors used')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Add monitor' })).not.toBeInTheDocument()
  })

  it('shows an error from the server', async () => {
    fakeApi({ ...signedIn, 'GET /api/monitors': problem(500, 'Something went wrong.') })
    renderApp('/monitors')
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong.')
  })
})

describe('add monitor form', () => {
  it('checks the fields before sending anything', async () => {
    const { calls } = fakeApi({ ...signedIn })
    const { user } = renderApp('/monitors/new')

    const url = await screen.findByLabelText('URL')
    await user.clear(url)
    await user.type(url, 'example.com')
    await user.clear(screen.getByLabelText('Timeout (ms)'))
    await user.type(screen.getByLabelText('Timeout (ms)'), '50')
    await user.click(screen.getByRole('button', { name: 'Add monitor' }))

    expect(screen.getByText('Name is required.')).toBeInTheDocument()
    expect(screen.getByText('Enter a full URL starting with http:// or https://')).toBeInTheDocument()
    expect(screen.getByText('Timeout must be between 1000 and 10000 ms.')).toBeInTheDocument()
    expect(screen.getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true')
    expect(calls.filter((c) => c.path === '/api/monitors')).toHaveLength(0)
  })

  it('shows the server\'s reason under the field (e.g. a private address)', async () => {
    fakeApi({ ...signedIn, 'POST /api/monitors': problem(400, 'One or more fields are invalid.', { url: 'That address is private or reserved and cannot be monitored.' }) })
    const { user } = renderApp('/monitors/new')

    await user.type(await screen.findByLabelText('Name'), 'Router')
    await user.type(screen.getByLabelText('URL'), '192.168.1.1/')
    await user.click(screen.getByRole('button', { name: 'Add monitor' }))

    expect(await screen.findByText('That address is private or reserved and cannot be monitored.')).toBeInTheDocument()
  })

  it('sends the monitor and opens its page', async () => {
    const { calls } = fakeApi({
      ...signedIn,
      'POST /api/monitors': { status: 201, body: monitor('new1', { name: 'Shop' }) },
      'GET /api/monitors/new1': { body: monitor('new1', { name: 'Shop', state: 'UNKNOWN' }) },
      'GET /api/monitors/new1/stats?window=24h': stats([]),
      'GET /api/incidents?monitorId=new1': { body: [] },
      'GET /api/monitors/new1/checks?size=25': { body: { items: [], page: 0, size: 25, hasMore: false } },
    })
    const { user } = renderApp('/monitors/new')

    await user.type(await screen.findByLabelText('Name'), '  Shop ')
    await user.type(screen.getByLabelText('URL'), 'shop.example.com/')
    await user.selectOptions(screen.getByLabelText('Check every'), '30')
    await user.type(screen.getByLabelText('Expected status (optional)'), '204')
    await user.click(screen.getByRole('button', { name: 'Add monitor' }))

    expect(await screen.findByRole('heading', { name: 'Shop' })).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/monitors')!.body).toEqual({
      name: 'Shop', url: 'https://shop.example.com/', method: 'GET', intervalSeconds: 30, timeoutMs: 5000, expectedStatus: 204,
    })
  })
})
