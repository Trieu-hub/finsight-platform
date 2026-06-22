import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

// Client-side gate for ADMIN-only pages. UX only — the backend enforces ROLE_ADMIN
// on /api/v1/auth/admin/** regardless of what the UI shows.
export default function AdminRoute() {
  const { isAdmin } = useAuth()
  return isAdmin ? <Outlet /> : <Navigate to="/" replace />
}
