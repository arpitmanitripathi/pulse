import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Incident } from '../api/types'
import { fakeApi } from '../test/fakeApi'
import { renderApp, signedIn } from '../test/renderApp'

const base: Incident = {
  id: 'i1', monitorId: 'm1', monitorName: 'API', openedAt: '2026-11-12T09:00:00Z', resolvedAt: '2026-11-12T09:12:00Z',
  resolution: 'RECOVERED', firstErrorType: 'TIMEOUT', summary: null, summaryStatus: 'PENDING', alerts: [],
}

function show(incident: Partial<Incident>) {
  fakeApi({ ...signedIn, 'GET /api/incidents/i1': { body: { ...base, ...incident } } })
  renderApp('/incidents/i1')
}

describe('incident page', () => {
  it('shows the AI summary as plain text, never as HTML', async () => {
    show({ summaryStatus: 'DONE', summary: '<img src=x onerror=alert(1)> API timed out since 09:00.' })

    expect(await screen.findByText('<img src=x onerror=alert(1)> API timed out since 09:00.')).toBeInTheDocument()
    expect(document.querySelector('img')).toBeNull()
    expect(screen.getByRole('heading', { name: 'Resolved incident: API' })).toBeInTheDocument()
    expect(screen.getByText(/lasted 12 min/)).toBeInTheDocument()
  })

  it.each([
    ['PENDING', 'Writing a summary…'],
    ['FAILED', 'A summary could not be written this time. Alerts were not affected.'],
    ['SKIPPED', 'AI summaries are switched off on this server (no API key).'],
  ] as const)('summary %s', async (summaryStatus, text) => {
    show({ summaryStatus })
    expect(await screen.findByText(text, { exact: false })).toBeInTheDocument()
  })

  it('an incident closed by pausing says so, not "recovered"', async () => {
    show({ resolution: 'MONITOR_CHANGED' })

    expect(await screen.findByRole('heading', { name: 'Closed incident: API' })).toBeInTheDocument()
    expect(screen.getByText('Closed: the monitor was paused, resumed or given a new URL')).toBeInTheDocument()
    expect(screen.getByText('It had not recovered, so no recovery alert was sent.')).toBeInTheDocument()
    expect(screen.queryByText(/Recovered/)).not.toBeInTheDocument()
  })

  it('the timeline shows each alert delivery', async () => {
    show({
      resolvedAt: null,
      resolution: null,
      alerts: [
        { channelId: 'c1', channelName: 'Email', type: 'EMAIL', kind: 'DOWN', attempts: 1, sentAt: '2026-11-12T09:00:05Z', lastError: null, channelDeleted: false },
        { channelId: 'c2', channelName: 'Ops Slack', type: 'SLACK', kind: 'DOWN', attempts: 3, sentAt: null, lastError: 'Slack answered HTTP 500.', channelDeleted: false },
      ],
    })

    expect(await screen.findByText('Down alert to Email (email): sent')).toBeInTheDocument()
    expect(screen.getByText('Down alert to Ops Slack (slack): retrying')).toBeInTheDocument()
    expect(screen.getByText('3 attempts. Last error: Slack answered HTTP 500.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Ongoing incident: API' })).toBeInTheDocument()
  })

  it('an alert to a channel that was deleted later is still shown, marked as deleted', async () => {
    show({
      alerts: [{ channelId: null, channelName: 'My email', type: 'EMAIL', kind: 'DOWN', attempts: 1, sentAt: '2026-11-12T09:00:05Z', lastError: null, channelDeleted: true }],
    })

    expect(await screen.findByText('Down alert to My email (email, since deleted): sent')).toBeInTheDocument()
    expect(screen.queryByText(/No alerts sent/)).not.toBeInTheDocument()
  })

  it('says when nobody was alerted', async () => {
    show({})
    expect(await screen.findByText('No alerts sent: no alert channels were set up')).toBeInTheDocument()
  })
})
