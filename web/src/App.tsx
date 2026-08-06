import { Route, Routes } from 'react-router-dom'
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
  )
}
