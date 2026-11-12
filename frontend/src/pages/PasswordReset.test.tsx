import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeApi, problem } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

const noSession = { 'POST /api/auth/refresh': { status: 401 } }

describe('forgot password', () => {
  it('is linked from the login page and always shows the same neutral answer', async () => {
    const { calls } = fakeApi({ ...noSession, 'POST /api/auth/forgot-password': { status: 204 } })
    const { user } = renderApp('/login')

    await user.click(await screen.findByRole('link', { name: 'Forgot password?' }))
    await user.type(screen.getByLabelText('Email'), ' alice@example.com ')
    await user.click(screen.getByRole('button', { name: 'Send reset link' }))

    expect(await screen.findByText(/If an account exists for/)).toHaveTextContent('alice@example.com')
    expect(calls.find((c) => c.path === '/api/auth/forgot-password')!.body).toEqual({ email: 'alice@example.com' })
  })

  it('needs an email', async () => {
    const { calls } = fakeApi(noSession)
    const { user } = renderApp('/forgot-password')

    await user.click(await screen.findByRole('button', { name: 'Send reset link' }))
    expect(screen.getByText('Email is required.')).toBeInTheDocument()
    expect(calls.map((c) => c.path)).toEqual(['/api/auth/refresh'])
  })
})

describe('reset password', () => {
  it('sets the new password and goes to login with a notice', async () => {
    const { calls } = fakeApi({ ...noSession, 'POST /api/auth/reset-password': { status: 204 } })
    const { user } = renderApp('/reset-password?token=abc123')

    await user.type(await screen.findByLabelText('New password'), 'a-brand-new-passphrase')
    await user.type(screen.getByLabelText('Repeat new password'), 'a-brand-new-passphrase')
    await user.click(screen.getByRole('button', { name: 'Set new password' }))

    expect(await screen.findByText(/Your password was changed and you were logged out everywhere/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Log in' })).toBeInTheDocument()
    expect(calls.find((c) => c.path === '/api/auth/reset-password')!.body).toEqual({ token: 'abc123', password: 'a-brand-new-passphrase' })
  })

  it('checks the two passwords match before sending', async () => {
    const { calls } = fakeApi(noSession)
    const { user } = renderApp('/reset-password?token=abc123')

    await user.type(await screen.findByLabelText('New password'), 'a-brand-new-passphrase')
    await user.type(screen.getByLabelText('Repeat new password'), 'a-different-passphrase')
    await user.click(screen.getByRole('button', { name: 'Set new password' }))

    expect(screen.getByText("The passwords don't match.")).toBeInTheDocument()
    expect(calls.map((c) => c.path)).toEqual(['/api/auth/refresh'])
  })

  it('shows why the server refused: a common password, or a used or expired link', async () => {
    let attempt = 0
    fakeApi({
      ...noSession,
      'POST /api/auth/reset-password': () =>
        ++attempt === 1
          ? problem(400, 'One or more fields are invalid.', { password: 'This password is too common and easy to guess. Choose another one.' })
          : problem(400, 'This reset link is invalid or has expired. Ask for a new one.'),
    })
    const { user } = renderApp('/reset-password?token=abc123')

    await user.type(await screen.findByLabelText('New password'), 'password123')
    await user.type(screen.getByLabelText('Repeat new password'), 'password123')
    await user.click(screen.getByRole('button', { name: 'Set new password' }))
    expect(await screen.findByText('This password is too common and easy to guess. Choose another one.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Set new password' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This reset link is invalid or has expired. Ask for a new one.')
    expect(screen.getByRole('link', { name: 'Get a new link' })).toHaveAttribute('href', '/forgot-password')
  })

  it('a link without a token says so', async () => {
    fakeApi(noSession)
    renderApp('/reset-password')
    expect(await screen.findByRole('heading', { name: 'Reset link incomplete' })).toBeInTheDocument()
  })
})
