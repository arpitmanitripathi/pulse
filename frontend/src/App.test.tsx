import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeApi, problem } from './test/fakeApi'
import { renderApp, signedIn } from './test/renderApp'

describe('session', () => {
  it('a visitor without a session is sent to the login page', async () => {
    fakeApi({ 'POST /api/auth/refresh': { status: 401 } })
    renderApp('/monitors')
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
  })

  it('the refresh cookie brings the session back on load', async () => {
    fakeApi({ ...signedIn, 'GET /api/monitors': { body: [] } })
    renderApp('/monitors')
    expect(await screen.findByText('No monitors yet')).toBeInTheDocument()
    expect(screen.getByText('alice@example.com')).toBeInTheDocument()
  })

  it('logging in goes back to the page that was asked for', async () => {
    const { calls } = fakeApi({
      'POST /api/auth/refresh': { status: 401 },
      'POST /api/auth/login': { body: { accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 900 } },
      'GET /api/me': { body: { id: 'u1', email: 'alice@example.com' } },
      'GET /api/alert-channels': { body: [] },
    })
    const { user } = renderApp('/alerts')

    await user.type(await screen.findByLabelText('Email'), 'alice@example.com')
    await user.type(screen.getByLabelText('Password'), 'a-good-password')
    await user.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByRole('heading', { name: 'Alerts' })).toBeInTheDocument()
    expect(calls.find((c) => c.path === '/api/auth/login')!.body).toEqual({ email: 'alice@example.com', password: 'a-good-password' })
  })

  it('a wrong password shows the server message', async () => {
    fakeApi({ 'POST /api/auth/refresh': { status: 401 }, 'POST /api/auth/login': problem(401, 'Wrong email or password.') })
    const { user } = renderApp('/login')

    await user.type(await screen.findByLabelText('Email'), 'alice@example.com')
    await user.type(screen.getByLabelText('Password'), 'nope-nope-nope')
    await user.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong email or password.')
  })

  it('register checks the password length before calling the server', async () => {
    const { calls } = fakeApi({ 'POST /api/auth/refresh': { status: 401 } })
    const { user } = renderApp('/register')

    await user.type(await screen.findByLabelText('Email'), 'alice@example.com')
    await user.type(screen.getByLabelText('Password'), 'short')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(screen.getByText('Password must be 10 to 72 characters.')).toBeInTheDocument()
    expect(calls.map((c) => c.path)).toEqual(['/api/auth/refresh'])
  })

  it('the public status page never asks for a session', async () => {
    const { calls } = fakeApi({ 'GET /api/public/status/acme': problem(404, 'Status page not found.') })
    renderApp('/s/acme')
    expect(await screen.findByText('Status page not found')).toBeInTheDocument()
    expect(calls.map((c) => c.path)).toEqual(['/api/public/status/acme'])
  })
})

describe('log out', () => {
  it('logging back in after a deliberate log-out starts at the dashboard, not the old page', async () => {
    let signedInNow = true
    fakeApi({
      'POST /api/auth/refresh': () => (signedInNow ? { body: { accessToken: 'token-1' } } : { status: 401 }),
      'GET /api/me': { body: { id: 'u1', email: 'alice@example.com' } },
      'GET /api/alert-channels': { body: [] },
      'GET /api/monitors': { body: [] },
      'POST /api/auth/logout': () => {
        signedInNow = false
        return { status: 204 }
      },
      'POST /api/auth/login': { body: { accessToken: 'token-2', tokenType: 'Bearer', expiresIn: 900 } },
    })
    const { user } = renderApp('/alerts')

    await user.click(await screen.findByRole('button', { name: 'Log out' }))
    await user.type(await screen.findByLabelText('Email'), 'alice@example.com')
    await user.type(screen.getByLabelText('Password'), 'a-good-password')
    await user.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByText('No monitors yet')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Alerts' })).not.toBeInTheDocument()
  })
})

describe('too many attempts', () => {
  it('counts down live and keeps the button disabled until it may be tried again', async () => {
    fakeApi({
      'POST /api/auth/refresh': { status: 401 },
      'POST /api/auth/login': { ...problem(429, 'Too many requests. Try again in 2 seconds.'), headers: { 'Retry-After': '2' } },
    })
    const { user } = renderApp('/login')

    await user.type(await screen.findByLabelText('Email'), 'alice@example.com')
    await user.type(screen.getByLabelText('Password'), 'wrong-password-1')
    await user.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByText('Too many attempts from this network. Try again in 2 s.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Log in' })).toBeDisabled()
    expect(await screen.findByText('Too many attempts from this network. Try again in 1 s.', {}, { timeout: 2000 })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled(), { timeout: 2500 })
    expect(screen.queryByText(/Too many attempts/)).not.toBeInTheDocument()
  })
})

describe('return path after login', () => {
  it('never follows a path that leaves the site', async () => {
    fakeApi({ ...signedIn, 'GET /api/monitors': { body: [] } })
    const { render } = await import('@testing-library/react')
    const { MemoryRouter } = await import('react-router')
    const { App } = await import('./App')
    render(
      <MemoryRouter initialEntries={[{ pathname: '/login', state: { from: '//evil.example/steal' } }]}>
        <App />
      </MemoryRouter>,
    )
    expect(await screen.findByText('No monitors yet')).toBeInTheDocument()
  })
})
