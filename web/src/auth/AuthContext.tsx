import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import { tokenStore } from '../api/client'
import { decodeJwt } from '../lib/jwt'

interface AuthState {
  token: string | null
  role: string | null
  email: string | null
  isAdmin: boolean
  isGamer: boolean
  // May open the LuckyMe mini-games section: gamers and admins only.
  canPlayLuckyMe: boolean
  isAuthenticated: boolean
  signIn: (accessToken: string, refreshToken?: string) => void
  signOut: () => void
}

const AuthContext = createContext<AuthState | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => tokenStore.getAccess())

  const value = useMemo<AuthState>(() => {
    const payload = decodeJwt(token)
    const role = payload?.role ?? null
    const isAdmin = role === 'ROLE_ADMIN'
    const isGamer = role === 'ROLE_GAMER'
    return {
      token,
      role,
      email: payload?.email ?? null,
      isAdmin,
      isGamer,
      canPlayLuckyMe: isAdmin || isGamer,
      isAuthenticated: !!token,
      signIn: (accessToken: string, refreshToken?: string) => {
        tokenStore.set(accessToken, refreshToken)
        setToken(accessToken)
      },
      signOut: () => {
        tokenStore.clear()
        setToken(null)
      },
    }
  }, [token])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
