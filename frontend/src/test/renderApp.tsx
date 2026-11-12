import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { App } from '../App'
import { setAccessToken } from '../api/client'

export const signedIn = {
  'POST /api/auth/refresh': { body: { accessToken: 'token-1' } },
  'GET /api/me': { body: { id: 'u1', email: 'alice@example.com' } },
}

/** The whole app at `path`, as a browser would load it. Combine with fakeApi(...) first. */
export function renderApp(path: string) {
  setAccessToken(null)
  const user = userEvent.setup()
  return { user, ...render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>) }
}
