import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeApi, problem } from '../test/fakeApi'
import { renderApp, signedIn } from '../test/renderApp'

const slack = { id: 'c1', type: 'SLACK', name: 'Ops', target: 'https://hooks.slack.com/services/T0…', createdAt: '2026-11-12T10:00:00Z' }

describe('alerts page', () => {
  it('the test button shows success or the reason it failed', async () => {
    let attempt = 0
    fakeApi({
      ...signedIn,
      'GET /api/alert-channels': { body: [slack] },
      'POST /api/alert-channels/c1/test': () => (++attempt === 1 ? { body: { delivered: true } } : problem(502, 'Slack answered HTTP 404: the webhook was removed.')),
    })
    const { user } = renderApp('/alerts')

    await user.click(await screen.findByRole('button', { name: 'Send test' }))
    expect(await screen.findByText('Test alert sent. Check that it arrived.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Send test' }))
    expect(await screen.findByText('Slack answered HTTP 404: the webhook was removed.')).toBeInTheDocument()
  })

  it('adds a channel and shows the server\'s field errors', async () => {
    let created = false
    const { calls } = fakeApi({
      ...signedIn,
      'GET /api/alert-channels': () => ({ body: created ? [slack] : [] }),
      'POST /api/alert-channels': (r) => {
        if ((r.body as { target: string }).target.startsWith('https://example.com')) {
          return problem(400, 'One or more fields are invalid.', { target: 'Slack webhooks must start with https://hooks.slack.com/services/' })
        }
        created = true
        return { status: 201, body: slack }
      },
    })
    const { user } = renderApp('/alerts')

    await user.click(await screen.findByRole('button', { name: 'Add channel' }))
    await user.selectOptions(screen.getByLabelText('Type'), 'SLACK')
    await user.type(screen.getByLabelText('Name'), 'Ops')
    await user.type(screen.getByLabelText('Webhook URL'), 'https://example.com/hook')
    await user.click(screen.getByRole('button', { name: 'Add channel' }))
    expect(await screen.findByText('Slack webhooks must start with https://hooks.slack.com/services/')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Webhook URL'))
    await user.type(screen.getByLabelText('Webhook URL'), 'https://hooks.slack.com/services/T0/B0/x')
    await user.click(screen.getByRole('button', { name: 'Add channel' }))

    const row = (await screen.findByText('https://hooks.slack.com/services/T0…')).closest('li')!
    expect(within(row).getByText('Slack')).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'POST' && c.path === '/api/alert-channels').at(-1)!.body).toEqual({ type: 'SLACK', name: 'Ops', target: 'https://hooks.slack.com/services/T0/B0/x' })
  })
})
