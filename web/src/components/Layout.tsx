import { useEffect, useRef, useState, type ReactNode } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n'
import NotificationBell from './NotificationBell'
import OnboardingTour from './OnboardingTour'
import LanguageToggle from './LanguageToggle'
import { markTourSeen, shouldAutoOpenTour } from '../lib/onboarding'

// Inline icons keep the nav lightweight (no icon library). 18px, stroked.
const icons: Record<string, ReactNode> = {
  '/': (
    <path d="M3 10.5 12 4l9 6.5M5 9.5V20h14V9.5" />
  ),
  '/transactions': (
    <>
      <path d="M4 7h16M4 7l3-3M4 7l3 3" />
      <path d="M20 17H4m16 0-3-3m3 3-3 3" />
    </>
  ),
  '/budgets': (
    <>
      <circle cx="12" cy="12" r="8" />
      <path d="M12 12V6m0 6 4.5 2.5" />
    </>
  ),
  '/wallets': (
    <>
      <rect x="3" y="6" width="18" height="13" rx="2" />
      <path d="M3 10h18" />
      <circle cx="17" cy="14" r="1" />
    </>
  ),
  '/analytics': (
    <>
      <path d="M4 19V5" />
      <path d="M4 19h16" />
      <path d="M8 16v-4m4 4V8m4 8v-6" />
    </>
  ),
  '/luckyme': (
    <>
      <circle cx="12" cy="12" r="8" />
      <path d="M12 4v16M8.5 9.5a3.5 3.5 0 0 1 3.5-1.5c1.8 0 3 .9 3 2s-1.2 1.8-3 2-3 .9-3 2 1.2 2 3 2a3.5 3.5 0 0 0 3.5-1.5" />
    </>
  ),
  '/admin': (
    <>
      <circle cx="12" cy="8" r="3.2" />
      <path d="M5.5 19a6.5 6.5 0 0 1 13 0" />
    </>
  ),
}

const baseLinks = [
  { to: '/', labelKey: 'nav.dashboard', end: true },
  { to: '/transactions', labelKey: 'nav.transactions' },
  { to: '/budgets', labelKey: 'nav.budgets' },
  { to: '/wallets', labelKey: 'nav.wallets' },
  { to: '/analytics', labelKey: 'nav.analytics' },
  { to: '/luckyme', labelKey: 'nav.luckyme' },
]

function NavIcon({ to }: { to: string }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      className="h-[18px] w-[18px]"
    >
      {icons[to]}
    </svg>
  )
}

export default function Layout() {
  const { signOut, isAdmin } = useAuth()
  const { t } = useI18n()
  const navigate = useNavigate()

  // Guided tour: auto-open for a brand-new account (or the first visit on this browser),
  // then remember it was seen so it doesn't reopen.
  const [tourOpen, setTourOpen] = useState(false)
  useEffect(() => {
    if (shouldAutoOpenTour()) setTourOpen(true)
  }, [])
  const closeTour = () => {
    setTourOpen(false)
    markTourSeen()
  }

  // Navigation is collapsed into a single "Menu" dropdown. Close it on an outside
  // click or Esc; each link closes it on navigate.
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    if (!menuOpen) return
    const onDown = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMenuOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [menuOpen])

  // Admin link is shown only to ROLE_ADMIN (UX only; backend enforces access).
  const links = isAdmin ? [...baseLinks, { to: '/admin', labelKey: 'nav.admin' }] : baseLinks

  return (
    <div className="min-h-screen bg-neutral-950 text-neutral-200">
      <header className="sticky top-0 z-10 border-b border-neutral-800/80 bg-neutral-950/80 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-2 px-4 py-3.5 sm:px-5">
          <div className="flex items-center gap-2 sm:gap-3">
            <div className="relative" ref={menuRef}>
              <button
                onClick={() => setMenuOpen((o) => !o)}
                aria-haspopup="menu"
                aria-expanded={menuOpen}
                className="flex items-center gap-2 rounded-lg border border-neutral-800 px-2.5 py-1.5 text-sm font-medium text-neutral-300 transition hover:border-neutral-700 hover:bg-neutral-800 hover:text-neutral-100"
              >
                <svg
                  xmlns="http://www.w3.org/2000/svg"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  className="h-[18px] w-[18px]"
                >
                  <path d="M4 6h16M4 12h16M4 18h16" />
                </svg>
                <span>{t('nav.menu')}</span>
              </button>
              {menuOpen && (
                <div
                  role="menu"
                  className="absolute left-0 top-full z-20 mt-2 w-56 overflow-hidden rounded-xl border border-neutral-800 bg-neutral-900 shadow-2xl shadow-black/60"
                >
                  <nav className="p-1.5">
                    {links.map((l) => (
                      <NavLink
                        key={l.to}
                        to={l.to}
                        end={l.end}
                        onClick={() => setMenuOpen(false)}
                        className={({ isActive }) =>
                          `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition ${
                            isActive
                              ? 'bg-emerald-500/15 text-emerald-300'
                              : 'text-neutral-300 hover:bg-neutral-800 hover:text-neutral-100'
                          }`
                        }
                      >
                        <NavIcon to={l.to} />
                        <span>{t(l.labelKey)}</span>
                      </NavLink>
                    ))}
                  </nav>
                  <div className="border-t border-neutral-800 p-1.5">
                    <button
                      onClick={() => {
                        setMenuOpen(false)
                        signOut()
                        navigate('/login')
                      }}
                      className="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium text-neutral-400 transition hover:bg-neutral-800 hover:text-neutral-100"
                    >
                      <svg
                        xmlns="http://www.w3.org/2000/svg"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.7"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        className="h-[18px] w-[18px]"
                      >
                        <path d="M15 12H3m0 0 4-4m-4 4 4 4" />
                        <path d="M9 4h9a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H9" />
                      </svg>
                      <span>{t('nav.signOut')}</span>
                    </button>
                  </div>
                </div>
              )}
            </div>
            <span className="bg-gradient-to-r from-emerald-400 to-teal-400 bg-clip-text text-lg font-extrabold tracking-tight text-transparent">
              Vernfy
            </span>
          </div>
          <div className="flex items-center gap-2">
            <LanguageToggle />
            <button
              onClick={() => setTourOpen(true)}
              data-tour="help-btn"
              aria-label={t('nav.tour')}
              title={t('nav.tour')}
              className="flex h-[34px] w-[34px] items-center justify-center rounded-lg border border-neutral-800 text-sm font-bold text-neutral-400 transition hover:border-neutral-700 hover:bg-neutral-800 hover:text-neutral-100"
            >
              ?
            </button>
            <span data-tour="notif-bell" className="inline-flex">
              <NotificationBell />
            </span>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl overflow-x-clip px-4 py-8 sm:px-5 sm:py-10">
        <Outlet />
      </main>
      <footer className="mx-auto max-w-6xl px-5 py-8 text-center text-xs text-neutral-600">
        Vernfy · {t('footer.tagline')} · Spring Boot · Kafka · React
      </footer>

      <OnboardingTour open={tourOpen} onClose={closeTour} />
    </div>
  )
}
