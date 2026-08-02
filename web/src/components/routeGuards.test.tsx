import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import ProtectedRoute from './ProtectedRoute'
import AdminRoute from './AdminRoute'
import LuckyMeRoute from './LuckyMeRoute'

// The three guards are one behaviour in three variants — "does this role get past, and where does
// it land otherwise" — so they share a file and a harness rather than repeating the router setup.
// They are driven through the real AuthProvider (token in storage) instead of a stubbed context:
// the claim-to-permission mapping is exactly what would break, and stubbing it would hide that.

function token(role: string) {
  const body = btoa(JSON.stringify({ role })).replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${body}.signature`
}

function signedInAs(role: string | null) {
  if (role) localStorage.setItem('finsight_token', token(role))
}

/** Renders `guard` over a protected page at /secret, with landing pages for both redirects. */
function renderGuard(guard: React.ReactElement) {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/secret']}>
        <Routes>
          <Route element={guard}>
            <Route path="/secret" element={<p>secret page</p>} />
          </Route>
          <Route path="/login" element={<p>login page</p>} />
          <Route path="/" element={<p>home page</p>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('route guards', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  describe('ProtectedRoute', () => {
    it('sends a signed-out visitor to the login page', () => {
      renderGuard(<ProtectedRoute />)

      expect(screen.getByText('login page')).toBeInTheDocument()
      expect(screen.queryByText('secret page')).not.toBeInTheDocument()
    })

    it('lets any signed-in user through', () => {
      signedInAs('ROLE_USER')

      renderGuard(<ProtectedRoute />)

      expect(screen.getByText('secret page')).toBeInTheDocument()
    })
  })

  describe('AdminRoute', () => {
    it('sends a non-admin home rather than to login', () => {
      // They are signed in — bouncing them to /login would be wrong and confusing.
      signedInAs('ROLE_USER')

      renderGuard(<AdminRoute />)

      expect(screen.getByText('home page')).toBeInTheDocument()
    })

    it('lets an admin through', () => {
      signedInAs('ROLE_ADMIN')

      renderGuard(<AdminRoute />)

      expect(screen.getByText('secret page')).toBeInTheDocument()
    })
  })

  describe('LuckyMeRoute', () => {
    it('lets a gamer through', () => {
      signedInAs('ROLE_GAMER')

      renderGuard(<LuckyMeRoute />)

      expect(screen.getByText('secret page')).toBeInTheDocument()
    })

    it('lets an admin through too', () => {
      signedInAs('ROLE_ADMIN')

      renderGuard(<LuckyMeRoute />)

      expect(screen.getByText('secret page')).toBeInTheDocument()
    })

    it('sends an ordinary user home', () => {
      signedInAs('ROLE_USER')

      renderGuard(<LuckyMeRoute />)

      expect(screen.getByText('home page')).toBeInTheDocument()
    })
  })
})
