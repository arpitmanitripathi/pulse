import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeApi, problem } from '../test/fakeApi'
import { renderApp, signedIn } from '../test/renderApp'

const monitors = ['API', 'Website', 'Docs'].map((name, i) => ({
  id: `m${i}`, name, url: 'https://example.com/', method: 'GET', intervalSeconds: 60, timeoutMs: 5000, expectedStatus: null,
  active: true, state: 'UP', uptime24h: 100, lastCheckedAt: null, createdAt: '2026-11-12T10:00:00Z',
}))

describe('status pages', () => {
  it('creates a page with a suggested address and the monitors in the chosen order', async () => {
    const { calls } = fakeApi({
      ...signedIn,
      'GET /api/monitors': { body: monitors },
      'GET /api/status-pages': { body: [] },
      'POST /api/status-pages': { status: 201, body: { id: 'p1', slug: 'acme-status', title: 'Acme Status', monitorIds: [], createdAt: '' } },
    })
    const { user } = renderApp('/status-pages')

    await user.click(await screen.findByRole('button', { name: 'New status page' }))
    await user.type(screen.getByLabelText('Title'), 'Acme Status')
    expect(screen.getByLabelText('Address')).toHaveValue('acme-status')
    await user.click(screen.getByLabelText('Website'))
    await user.click(screen.getByLabelText('API'))
    await user.click(screen.getByRole('button', { name: 'Move API up' }))
    await user.click(screen.getByRole('button', { name: 'Create page' }))

    await screen.findByRole('button', { name: 'New status page' })
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/status-pages')!.body).toEqual({ title: 'Acme Status', slug: 'acme-status', monitorIds: ['m0', 'm1'] })
  })

  it('a taken address is shown under the address field', async () => {
    fakeApi({
      ...signedIn,
      'GET /api/monitors': { body: monitors },
      'GET /api/status-pages': { body: [] },
      'POST /api/status-pages': problem(409, 'That address is already taken. Choose another.'),
    })
    const { user } = renderApp('/status-pages')

    await user.click(await screen.findByRole('button', { name: 'New status page' }))
    await user.type(screen.getByLabelText('Title'), 'Acme')
    await user.click(screen.getByRole('button', { name: 'Create page' }))
    expect(await screen.findByText('That address is already taken. Choose another.')).toBeInTheDocument()
    expect(screen.getByLabelText('Address')).toHaveAttribute('aria-invalid', 'true')
  })

  it('lists pages with their public link', async () => {
    fakeApi({
      ...signedIn,
      'GET /api/monitors': { body: monitors },
      'GET /api/status-pages': { body: [{ id: 'p1', slug: 'acme', title: 'Acme', monitorIds: ['m2', 'm0'], createdAt: '' }] },
    })
    renderApp('/status-pages')

    expect(await screen.findByRole('link', { name: /\/s\/acme/ })).toHaveAttribute('href', '/s/acme')
    expect(screen.getByText('Docs · API')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New status page' })).toBeInTheDocument()
  })
})
