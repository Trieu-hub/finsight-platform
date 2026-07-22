import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'vernfy.theme'

/**
 * Saved choice wins; otherwise follow the OS. Read synchronously during the first render so the
 * class is on <html> before paint — a state-then-effect approach would flash the wrong theme.
 */
function initialTheme(): Theme {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'light' || saved === 'dark') return saved
    return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
  } catch {
    return 'dark' // storage blocked, or no matchMedia — the app's original look
  }
}

/**
 * Long enough to cover the slowest thing on screen: the page fade is 900ms and the toggle's last
 * beat (the sun's rays) starts at 520ms and runs 660ms. Anything shorter and the class is pulled
 * mid-move, which snaps the remaining colours into place.
 */
const TRANSITION_WINDOW_MS = 1300

/**
 * Persists the choice and arms the page-wide colour cross-fade for the length of the switch.
 * The class is temporary on purpose: left on permanently it would also animate ordinary hover
 * states, which have to stay instant.
 */
function commit(next: Theme): Theme {
  const root = document.documentElement
  root.classList.add('theme-transition')
  window.setTimeout(() => root.classList.remove('theme-transition'), TRANSITION_WINDOW_MS)
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // non-fatal (e.g. storage blocked) — the theme still applies for this session
  }
  return next
}

type ThemeValue = {
  theme: Theme
  setTheme: (t: Theme) => void
  toggle: () => void
}

const ThemeContext = createContext<ThemeValue | null>(null)

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(initialTheme)

  // Reflect the theme onto <html> for the `dark:` variant and the CSS variables in index.css.
  // color-scheme additionally themes the scrollbars and native form controls.
  useEffect(() => {
    const root = document.documentElement
    root.classList.toggle('dark', theme === 'dark')
    root.style.colorScheme = theme
  }, [theme])

  const setTheme = useCallback((t: Theme) => {
    setThemeState((current) => (current === t ? current : commit(t)))
  }, [])

  const toggle = useCallback(() => {
    setThemeState((current) => commit(current === 'dark' ? 'light' : 'dark'))
  }, [])

  const value = useMemo(() => ({ theme, setTheme, toggle }), [theme, setTheme, toggle])
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme(): ThemeValue {
  const ctx = useContext(ThemeContext)
  if (!ctx) throw new Error('useTheme must be used inside a ThemeProvider')
  return ctx
}
