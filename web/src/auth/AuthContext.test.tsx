import { render, screen, act } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { AuthProvider, useAuth } from './AuthContext'

// A token the app would accept for reading claims: only the payload segment is ever decoded
// (decodeJwt does no verification), so header and signature can be anything.
function token(payload: Record<string, unknown>) {
  const body = btoa(JSON.stringify(payload)).replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${body}.signature`
}

/** Renders the auth state as text so assertions read off the DOM, not off internals. */
function Probe() {
  const auth = useAuth()
  return (
    <div>
      <span data-testid="role">{auth.role ?? 'none'}</span>
      <span data-testid="email">{auth.email ?? 'none'}</span>
      <span data-testid="authenticated">{String(auth.isAuthenticated)}</span>
      <span data-testid="admin">{String(auth.isAdmin)}</span>
      <span data-testid="luckyme">{String(auth.canPlayLuckyMe)}</span>
      <button onClick={() => auth.signIn(token({ role: 'ROLE_USER', email: 'u@vernfy.com' }), 'r1')}>
        sign in
      </button>
      <button onClick={auth.signOut}>sign out</button>
    </div>
  )
}

function renderProbe() {
  return render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  )
}

const value = (id: string) => screen.getByTestId(id).textContent

describe('AuthProvider', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('starts signed out when no token is stored', () => {
    renderProbe()

    expect(value('authenticated')).toBe('false')
    expect(value('role')).toBe('none')
    expect(value('admin')).toBe('false')
  })

  it('reads the role and email out of a token already in storage', () => {
    localStorage.setItem('finsight_token', token({ role: 'ROLE_ADMIN', email: 'a@vernfy.com' }))

    renderProbe()

    expect(value('authenticated')).toBe('true')
    expect(value('role')).toBe('ROLE_ADMIN')
    expect(value('email')).toBe('a@vernfy.com')
  })

  it('lets admins into LuckyMe as well as gamers, and nobody else', () => {
    // Mirrors the backend: ROLE_GAMER gates the mini-games, ROLE_ADMIN is allowed too.
    localStorage.setItem('finsight_token', token({ role: 'ROLE_GAMER' }))
    const { unmount } = renderProbe()
    expect(value('luckyme')).toBe('true')
    expect(value('admin')).toBe('false')
    unmount()

    localStorage.setItem('finsight_token', token({ role: 'ROLE_ADMIN' }))
    const admin = renderProbe()
    expect(value('luckyme')).toBe('true')
    admin.unmount()

    localStorage.setItem('finsight_token', token({ role: 'ROLE_USER' }))
    renderProbe()
    expect(value('luckyme')).toBe('false')
  })

  it('signIn persists both tokens and re-derives the claims', () => {
    renderProbe()

    act(() => screen.getByText('sign in').click())

    expect(value('authenticated')).toBe('true')
    expect(value('role')).toBe('ROLE_USER')
    expect(value('email')).toBe('u@vernfy.com')
    expect(localStorage.getItem('finsight_token')).not.toBeNull()
    expect(localStorage.getItem('finsight_refresh')).toBe('r1')
  })

  it('signOut clears both tokens from storage', () => {
    localStorage.setItem('finsight_token', token({ role: 'ROLE_USER' }))
    localStorage.setItem('finsight_refresh', 'r1')
    renderProbe()

    act(() => screen.getByText('sign out').click())

    expect(value('authenticated')).toBe('false')
    expect(localStorage.getItem('finsight_token')).toBeNull()
    expect(localStorage.getItem('finsight_refresh')).toBeNull()
  })

  it('refuses to be used outside the provider', () => {
    // A silent undefined context would surface much later as "cannot read role of undefined".
    expect(() => render(<Probe />)).toThrow(/AuthProvider/)
  })

  it('treats a malformed token as signed in but role-less rather than crashing', () => {
    // decodeJwt swallows the parse error; the UI must still render, just without a role.
    localStorage.setItem('finsight_token', 'not-a-jwt')

    renderProbe()

    expect(value('authenticated')).toBe('true')
    expect(value('role')).toBe('none')
    expect(value('admin')).toBe('false')
  })
})
