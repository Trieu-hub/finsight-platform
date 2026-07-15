import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'

// Client-side gate for the LuckyMe mini-games. Only gamers and admins may enter; everyone
// else is redirected home. UX only — the game API also enforces ROLE_GAMER/ROLE_ADMIN.
export default function LuckyMeRoute() {
  const { canPlayLuckyMe } = useAuth()
  return canPlayLuckyMe ? <Outlet /> : <Navigate to="/" replace />
}
