import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import NotificationBell from './NotificationBell'

const baseLinks = [
  { to: '/', label: 'Dashboard', end: true },
  { to: '/transactions', label: 'Transactions' },
  { to: '/budgets', label: 'Budgets' },
]

export default function Layout() {
  const { signOut, isAdmin } = useAuth()
  const navigate = useNavigate()

  // Admin link is shown only to ROLE_ADMIN (UX only; backend enforces access).
  const links = isAdmin ? [...baseLinks, { to: '/admin', label: 'Admin' }] : baseLinks

  return (
    <div className="min-h-screen bg-neutral-950 text-neutral-200">
      <header className="sticky top-0 z-10 border-b border-neutral-800 bg-neutral-950/80 backdrop-blur">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-4 py-3">
          <div className="flex items-center gap-6">
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
                    `rounded-lg px-3 py-1.5 text-sm font-medium transition ${
                      isActive
                        ? 'bg-emerald-500/15 text-emerald-300'
                        : 'text-neutral-400 hover:bg-neutral-800 hover:text-neutral-100'
                    }`
                  }
                >
                  {l.label}
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
      <main className="mx-auto max-w-5xl px-4 py-8">
        <Outlet />
      </main>
      <footer className="mx-auto max-w-5xl px-4 py-6 text-center text-xs text-neutral-600">
        FinSight · event-driven finance platform · Spring Boot · Kafka · React
      </footer>
    </div>
  )
}
