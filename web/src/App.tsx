import { Route, Routes } from 'react-router-dom'
import InstallBanner from './components/InstallBanner'
import UpdateBanner from './components/UpdateBanner'
import Layout from './components/Layout'
import ProtectedRoute from './components/ProtectedRoute'
import AdminRoute from './components/AdminRoute'
import LuckyMeRoute from './components/LuckyMeRoute'
import Login from './pages/Login'
import Register from './pages/Register'
import Dashboard from './pages/Dashboard'
import Transactions from './pages/Transactions'
import Budgets from './pages/Budgets'
import Wallets from './pages/Wallets'
import Analytics from './pages/Analytics'
import Import from './pages/Import'
import Admin from './pages/Admin'
import LuckyMe from './pages/LuckyMe'

export default function App() {
  return (
    <>
      {/*
       * Above the routes, not inside Layout: Layout only wraps the signed-in screens, so a banner
       * living there is invisible to exactly the person it is for — the first-time visitor sitting
       * on /login, who has not installed anything yet. The update banner has the same problem in
       * reverse: a new build must be offered even to someone stuck on the sign-in page.
       */}
      <UpdateBanner />
      <InstallBanner />
      <Routes>
      {/* Public */}
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />

      {/* Protected: must be signed in */}
      <Route element={<ProtectedRoute />}>
        <Route element={<Layout />}>
          <Route path="/" element={<Dashboard />} />
          <Route path="/transactions" element={<Transactions />} />
          <Route path="/budgets" element={<Budgets />} />
          <Route path="/wallets" element={<Wallets />} />
          <Route path="/analytics" element={<Analytics />} />
          <Route path="/import" element={<Import />} />
          {/* LuckyMe: gamers + admins only (also enforced server-side) */}
          <Route element={<LuckyMeRoute />}>
            <Route path="/luckyme" element={<LuckyMe />} />
          </Route>
          {/* Admin-only (also enforced server-side) */}
          <Route element={<AdminRoute />}>
            <Route path="/admin" element={<Admin />} />
          </Route>
        </Route>
      </Route>
      </Routes>
    </>
  )
}
