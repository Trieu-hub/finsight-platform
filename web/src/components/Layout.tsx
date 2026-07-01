import type { ReactNode } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import NotificationBell from './NotificationBell'

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
  '/admin': (
    <>
      <circle cx="12" cy="8" r="3.2" />
      <path d="M5.5 19a6.5 6.5 0 0 1 13 0" />
    </>
  ),
}

const baseLinks = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/transactions', label: 'Transactions' },
  { to: '/budgets', label: 'Budgets' },
  { to: '/wallets', label: 'Wallets' },
  { to: '/analytics', label: 'Analytics' },
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
  const navigate = useNavigate()

  // Admin link is shown only to ROLE_ADMIN (UX only; backend enforces access).
  const links = isAdmin ? [...baseLinks, { to: '/admin', label: 'Admin' }] : baseLinks

  return (
    <div className="min-h-screen bg-neutral-950 text-neutral-200">
      <header className="sticky top-0 z-10 border-b border-neutral-800/80 bg-neutral-950/80 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-5 py-3.5">
          <div className="flex items-center gap-7">
            <span className="bg-gradient-to-r from-emerald-400 to-teal-400 bg-clip-text text-lg font-extrabold tracking-tight text-transparent">
              FinSight
            </span>
            <nav className="flex gap-1">
              {links.map((l) => (
                <NavLink
                  key={l.to}
                  to={l.to}
                  end={l.end}
                  className={({ isActive }) =>
                    `flex items-center gap-2 rounded-lg px-3 py-1.5 text-sm font-medium transition ${
                      isActive
                        ? 'bg-emerald-500/15 text-emerald-300'
                        : 'text-neutral-400 hover:bg-neutral-800 hover:text-neutral-100'
                    }`
                  }
                >
                  <NavIcon to={l.to} />
                  <span className="hidden sm:inline">{l.label}</span>
                </NavLink>
              ))}
            </nav>
          </div>
          <div className="flex items-center gap-2">
            <NotificationBell />
            <button
              onClick={() => {
                signOut()
                navigate('/login')
              }}
              className="rounded-lg border border-neutral-800 px-3 py-1.5 text-sm font-medium text-neutral-400 transition hover:border-neutral-700 hover:bg-neutral-800 hover:text-neutral-100"
            >
              Sign out
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-5 py-10">
        <Outlet />
      </main>
      <footer className="mx-auto max-w-6xl px-5 py-8 text-center text-xs text-neutral-600">
        FinSight · event-driven finance platform · Spring Boot · Kafka · React
      </footer>
    </div>
  )
}
