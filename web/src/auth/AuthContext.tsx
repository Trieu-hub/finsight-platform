import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'
import { tokenStore } from '../api/client'

interface AuthState {
  token: string | null
  isAuthenticated: boolean
  signIn: (token: string) => void
  signOut: () => void
}

const AuthContext = createContext<AuthState | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => tokenStore.get())

  const value = useMemo<AuthState>(
    () => ({
      token,
      isAuthenticated: !!token,
      signIn: (t: string) => {
        tokenStore.set(t)
        setToken(t)
      },
      signOut: () => {
        tokenStore.clear()
        setToken(null)
      },
    }),
    [token],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
