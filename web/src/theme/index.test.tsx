import { act, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ThemeProvider, useTheme } from './index'

// jsdom has no matchMedia; the provider calls it when nothing is saved, so it has to be stubbed
// per test to choose what "follow the OS" means.
function osPrefersLight(prefersLight: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('light') ? prefersLight : !prefersLight,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

function Probe() {
  const { theme, toggle, setTheme } = useTheme()
  return (
    <div>
      <span data-testid="theme">{theme}</span>
      <button onClick={toggle}>toggle</button>
      <button onClick={() => setTheme('light')}>go light</button>
    </div>
  )
}

function renderProbe() {
  return render(
    <ThemeProvider>
      <Probe />
    </ThemeProvider>,
  )
}

const theme = () => screen.getByTestId('theme').textContent

describe('ThemeProvider', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.className = ''
    osPrefersLight(false)
  })

  it('follows the OS when nothing has been chosen', () => {
    osPrefersLight(true)

    renderProbe()

    expect(theme()).toBe('light')
  })

  it('prefers the saved choice over the OS setting', () => {
    osPrefersLight(true)
    localStorage.setItem('vernfy.theme', 'dark')

    renderProbe()

    expect(theme()).toBe('dark')
  })

  it('falls back to dark when storage or matchMedia is unavailable', () => {
    // Private-mode browsers throw on localStorage access; the app must still render.
    vi.stubGlobal('matchMedia', () => {
      throw new Error('unavailable')
    })

    renderProbe()

    expect(theme()).toBe('dark')
  })

  it('toggling flips the theme and persists it', () => {
    renderProbe()
    expect(theme()).toBe('dark')

    act(() => screen.getByText('toggle').click())

    expect(theme()).toBe('light')
    expect(localStorage.getItem('vernfy.theme')).toBe('light')
  })

  it('mirrors the theme onto <html> so the dark: variant and CSS variables follow', () => {
    renderProbe()
    expect(document.documentElement).toHaveClass('dark')

    act(() => screen.getByText('go light').click())

    expect(document.documentElement).not.toHaveClass('dark')
  })

  it('refuses to be used outside the provider', () => {
    expect(() => render(<Probe />)).toThrow(/ThemeProvider/)
  })
})
