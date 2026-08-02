import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { I18nProvider } from '../i18n'
import Login from './Login'

// Only the network call is faked. The form, the auth context, the router and the error mapping are
// the real ones — this test exists to cover the wiring between them, which is where sign-in breaks.
vi.mock('../api/endpoints', () => ({ login: vi.fn() }))
const { login } = await import('../api/endpoints')
const loginMock = vi.mocked(login)

type LoginResult = Awaited<ReturnType<typeof login>>

function token(role: string) {
  const body = btoa(JSON.stringify({ role })).replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${body}.signature`
}

function renderLogin() {
  return render(
    <I18nProvider>
      <AuthProvider>
        <MemoryRouter initialEntries={['/login']}>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/" element={<p>home page</p>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </I18nProvider>,
  )
}

function fillIn(email: string, password: string) {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: email } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
}

/** An error shaped the way axios reports a rejected response, so errorMessage() unwraps it. */
function apiError(message: string) {
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: { data: { error: { code: 'INVALID_CREDENTIALS', message } } },
  })
}

describe('Login', () => {
  beforeEach(() => {
    localStorage.clear()
    loginMock.mockReset()
  })

  it('renders the sign-in form', () => {
    renderLogin()

    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('submits the typed credentials, stores the tokens and lands on the dashboard', async () => {
    loginMock.mockResolvedValue({
      accessToken: token('ROLE_USER'),
      refreshToken: 'refresh-1',
    } as LoginResult)
    renderLogin()

    fillIn('u@vernfy.com', 'pw12345678')
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('home page')).toBeInTheDocument()
    expect(loginMock).toHaveBeenCalledWith('u@vernfy.com', 'pw12345678')
    expect(localStorage.getItem('finsight_token')).not.toBeNull()
    expect(localStorage.getItem('finsight_refresh')).toBe('refresh-1')
  })

  it('shows the API error message and stays on the page when sign-in is rejected', async () => {
    loginMock.mockRejectedValue(apiError('Invalid email or password'))
    renderLogin()

    fillIn('u@vernfy.com', 'wrong')
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Invalid email or password')).toBeInTheDocument()
    expect(screen.queryByText('home page')).not.toBeInTheDocument()
    expect(localStorage.getItem('finsight_token')).toBeNull()
  })

  it('treats a 200 without a token as a failed sign-in', async () => {
    // The backend's /register returns success with no token; a wiring slip could point the form at
    // an endpoint like that, and the user must not end up "signed in" with nothing stored.
    loginMock.mockResolvedValue({} as LoginResult)
    renderLogin()

    fillIn('u@vernfy.com', 'pw12345678')
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(screen.queryByText('home page')).not.toBeInTheDocument())
    expect(localStorage.getItem('finsight_token')).toBeNull()
  })

  it('disables the submit button while the request is in flight', async () => {
    let resolve: (v: LoginResult) => void = () => {}
    loginMock.mockReturnValue(new Promise<LoginResult>((r) => { resolve = r }))
    renderLogin()

    fillIn('u@vernfy.com', 'pw12345678')
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    const submitting = await screen.findByRole('button', { name: 'Signing in…' })
    expect(submitting).toBeDisabled()

    resolve({ accessToken: token('ROLE_USER') } as LoginResult)
    expect(await screen.findByText('home page')).toBeInTheDocument()
  })
})
