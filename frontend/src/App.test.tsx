import { screen } from '@testing-library/react'
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
