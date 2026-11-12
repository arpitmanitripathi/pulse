import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { PublicStatus } from '../api/types'
import { fakeApi } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

function days(last: number | null) {
  return Array.from({ length: 90 }, (_, i) => ({ date: `d${i}`, uptime: i === 89 ? last : i < 30 ? null : 100 }))
}

const page: PublicStatus = {
  title: 'Acme status',
  generatedAt: '2026-11-12T10:00:00Z',
  monitors: [
    { name: 'API', state: 'DOWN', uptime24h: 87.5, uptime7d: 99.1, uptime30d: 99.8, days: days(87.5),
      latency24h: [{ hour: '2026-11-12T08:00:00Z', latencyP50: 140, latencyP95: 300 }, { hour: '2026-11-12T09:00:00Z', latencyP50: null, latencyP95: null }] },
    { name: 'Website', state: 'UP', uptime24h: 100, uptime7d: 100, uptime30d: null, days: days(100), latency24h: [] },
  ],
  incidents: [
    { monitorName: 'API', openedAt: '2026-11-12T09:30:00Z', resolvedAt: null },
    { monitorName: 'Website', openedAt: '2026-11-01T09:30:00Z', resolvedAt: '2026-11-01T09:42:00Z' },
  ],
}

describe('public status page', () => {
  it('shows the banner, each service with 90 bars, and incidents', async () => {
    fakeApi({ 'GET /api/public/status/acme': { body: page } })
    renderApp('/s/acme')

    expect(await screen.findByRole('heading', { name: 'Acme status' })).toBeInTheDocument()
    expect(screen.getByText('1 of 2 services down')).toBeInTheDocument()
    expect(screen.getByText(/API is down since/)).toBeInTheDocument()

    const api = screen.getByRole('heading', { name: 'API' }).closest('li')!
    expect(within(api).getByText('Down')).toBeInTheDocument()
    expect(within(api).getByText('87.50%')).toBeInTheDocument()
    expect(within(api).getByText('140 ms')).toBeInTheDocument() // latest hour with data
    const bars = within(api).getByRole('list', { name: 'Daily uptime, last 90 days (UTC)' })
    expect(within(bars).getAllByRole('listitem')).toHaveLength(90)
    expect(within(bars).getAllByRole('listitem')[0]).toHaveAttribute('aria-label', 'd0: no data')

    const site = screen.getByRole('heading', { name: 'Website' }).closest('li')!
    expect(within(site).getAllByText('—')).toHaveLength(2) // no 30 d data, no latency
    expect(screen.getByText(/down 12 min/)).toBeInTheDocument()
  })

  it('all up', async () => {
    fakeApi({ 'GET /api/public/status/ok': { body: { ...page, monitors: [page.monitors[1]], incidents: [] } } })
    renderApp('/s/ok')
    expect(await screen.findByText('All systems operational')).toBeInTheDocument()
    expect(screen.getByText('No incidents in the last 30 days.')).toBeInTheDocument()
  })
})
