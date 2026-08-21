import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { AuthProvider } from './auth/AuthContext'
import { I18nProvider } from './i18n'
import { ThemeProvider } from './theme'

vi.mock('./api/endpoints', () => ({ login: vi.fn() }))

const IPHONE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Version/17.0 Safari/604.1'

function renderApp(path: string) {
  return render(
    <I18nProvider>
      <ThemeProvider>
        <AuthProvider>
          <MemoryRouter initialEntries={[path]}>
            <App />
          </MemoryRouter>
        </AuthProvider>
      </ThemeProvider>
    </I18nProvider>,
  )
}

describe('App-level banners', () => {
  beforeEach(() => {
    window.localStorage.clear()
    Object.defineProperty(window.navigator, 'userAgent', { value: IPHONE_UA, configurable: true })
    window.matchMedia = vi.fn().mockReturnValue({ matches: false }) as unknown as typeof matchMedia
  })

  /*
   * The regression this exists for: the install banner first shipped inside Layout, and Layout
   * only wraps the signed-in routes. So it was invisible to the one person it is written for —
   * a first-time visitor on /login, who by definition has not installed anything. Nothing was
   * broken, nothing logged; the banner simply never rendered where it mattered.
   */
  it('offers the install on the sign-in screen, where a new visitor actually lands', () => {
    renderApp('/login')

    expect(screen.getByText(/Add to Home Screen/i)).toBeInTheDocument()
  })

  it('still offers it on a signed-in route', () => {
    // Unauthenticated, so ProtectedRoute sends this to /login — which is the point: whichever
    // screen the visitor ends up on, the offer travels with them.
    renderApp('/transactions')

    expect(screen.getByText(/Add to Home Screen/i)).toBeInTheDocument()
  })

  it('says nothing once the app already runs from the home screen', () => {
    Object.defineProperty(window.navigator, 'standalone', { value: true, configurable: true })

    renderApp('/login')

    expect(screen.queryByText(/Add to Home Screen/i)).not.toBeInTheDocument()
    Object.defineProperty(window.navigator, 'standalone', { value: undefined, configurable: true })
  })
})
