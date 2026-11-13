import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeApi } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'

const noSession = { 'POST /api/auth/refresh': { status: 401 } }

describe('show / hide password', () => {
  it('the eye button reveals and hides the password on login', async () => {
    fakeApi(noSession)
    const { user } = renderApp('/login')

    const password = await screen.findByLabelText('Password')
    await user.type(password, 'secret-passphrase')
    expect(password).toHaveAttribute('type', 'password')

    await user.click(screen.getByRole('button', { name: 'Show password' }))
    expect(password).toHaveAttribute('type', 'text')
    expect(password).toHaveValue('secret-passphrase')
    expect(screen.getByRole('button', { name: 'Hide password' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(screen.getByRole('button', { name: 'Hide password' }))
    expect(password).toHaveAttribute('type', 'password')
  })

  it('register and both reset fields have it too, and it never submits the form', async () => {
    const { calls } = fakeApi(noSession)
    const { user, unmount } = renderApp('/register')
    await user.click(await screen.findByRole('button', { name: 'Show password' }))
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'text')
    expect(calls.map((c) => c.path)).toEqual(['/api/auth/refresh']) // no register request
    unmount()

    renderApp('/reset-password?token=abc')
    expect(await screen.findAllByRole('button', { name: 'Show password' })).toHaveLength(2)
  })
})
