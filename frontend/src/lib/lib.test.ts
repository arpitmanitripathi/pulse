import { describe, expect, it } from 'vitest'
import type { PublicMonitor } from '../api/types'
import { formatDuration, formatMs, formatUptime } from './format'
import { validateMonitor } from './monitorRules'
import { barColor, overall, SLUG, slugify } from './status'

const valid = { name: 'API', url: 'https://example.com/health', timeoutMs: '5000', expectedStatus: '' }

describe('monitor form rules (same as the backend)', () => {
  it('accepts a normal monitor', () => {
    expect(validateMonitor(valid)).toEqual({})
  })

  it.each([
    [{ name: ' ' }, 'name', 'Name is required.'],
    [{ name: 'x'.repeat(101) }, 'name', 'Name must be 1 to 100 characters.'],
    [{ url: '' }, 'url', 'URL is required.'],
    [{ url: 'example.com' }, 'url', 'Enter a full URL starting with http:// or https://'],
    [{ url: 'ftp://example.com' }, 'url', 'Enter a full URL starting with http:// or https://'],
    [{ timeoutMs: '999' }, 'timeoutMs', 'Timeout must be between 1000 and 10000 ms.'],
    [{ timeoutMs: '10001' }, 'timeoutMs', 'Timeout must be between 1000 and 10000 ms.'],
    [{ timeoutMs: '2.5' }, 'timeoutMs', 'Timeout must be between 1000 and 10000 ms.'],
    [{ expectedStatus: '99' }, 'expectedStatus', 'Expected status must be between 100 and 599.'],
    [{ expectedStatus: '600' }, 'expectedStatus', 'Expected status must be between 100 and 599.'],
  ])('%o → %s error', (change, field, message) => {
    expect(validateMonitor({ ...valid, ...change })[field]).toBe(message)
  })
})

describe('formatting', () => {
  it('uptime keeps the server value, with 2 decimals', () => {
    expect(formatUptime(99.99)).toBe('99.99%')
    expect(formatUptime(100)).toBe('100.00%')
    expect(formatUptime(null)).toBe('—')
  })

  it('milliseconds and seconds', () => {
    expect(formatMs(87)).toBe('87 ms')
    expect(formatMs(1500)).toBe('1.50 s')
    expect(formatMs(null)).toBe('—')
  })

  it('durations', () => {
    const t = '2026-11-12T10:00:00Z'
    expect(formatDuration(t, '2026-11-12T10:00:20Z')).toBe('under 1 min')
    expect(formatDuration(t, '2026-11-12T10:07:00Z')).toBe('7 min')
    expect(formatDuration(t, '2026-11-12T12:05:00Z')).toBe('2 h 5 min')
    expect(formatDuration(t, '2026-11-14T13:00:00Z')).toBe('2 d 3 h')
    expect(formatDuration(t, null, Date.parse('2026-11-12T11:00:00Z'))).toBe('1 h')
  })
})

describe('status pages', () => {
  it('slugify makes a valid address from a title', () => {
    expect(slugify('Acme Inc. Status!')).toBe('acme-inc-status')
    expect(slugify('  --Hello--  ')).toBe('hello')
    expect(slugify('a'.repeat(39) + ' b')).toBe('a'.repeat(39))
    expect(SLUG.test(slugify('Acme Inc. Status!'))).toBe(true)
  })

  it('slug rule matches the backend', () => {
    for (const ok of ['abc', 'acme-2', 'a'.repeat(40)]) expect(SLUG.test(ok)).toBe(true)
    for (const bad of ['ab', '-abc', 'abc-', 'ABC', 'a b c', 'a'.repeat(41)]) expect(SLUG.test(bad)).toBe(false)
  })

  it('bar colours', () => {
    expect(barColor(null)).toBe('bg-slate-200')
    expect(barColor(100)).toBe('bg-emerald-500')
    expect(barColor(99.5)).toBe('bg-lime-400')
    expect(barColor(97)).toBe('bg-amber-400')
    expect(barColor(50)).toBe('bg-red-500')
  })

  it('the banner ignores paused monitors', () => {
    const m = (state: PublicMonitor['state']) => ({ state }) as PublicMonitor
    expect(overall([m('UP'), m('UP'), m('PAUSED')]).text).toBe('All systems operational')
    expect(overall([m('UP'), m('DOWN')]).text).toBe('1 of 2 services down')
    expect(overall([m('DOWN'), m('PAUSED')]).text).toBe('Major outage')
    expect(overall([m('UNKNOWN')]).text).toBe('Status being checked')
    expect(overall([]).text).toBe('Status being checked')
  })
})
