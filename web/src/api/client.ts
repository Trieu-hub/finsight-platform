import axios from 'axios'

const TOKEN_KEY = 'finsight_token'

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t: string) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY),
}

// One axios instance for the whole app. baseURL is "/api/v1" — a relative path, so
// the browser hits the Vite dev server, which proxies to the gateway (see vite.config.ts).
export const api = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
})

// REQUEST interceptor: attach the Bearer token to every outgoing request, so we
// never repeat `headers: { Authorization: ... }` by hand (this is the automated
// version of the $headers you built in PowerShell).
api.interceptors.request.use((config) => {
  const token = tokenStore.get()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// RESPONSE interceptor: if the server says 401 (token missing/expired), drop the
// token and bounce to /login. Centralised here so every page gets it for free.
api.interceptors.response.use(
  (res) => res,
  (error) => {
    if (error.response?.status === 401) {
      tokenStore.clear()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  },
)

// Pull a human-readable message out of the backend error envelope
// ({ success:false, error:{ code, message } }).
export function errorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    return (
      err.response?.data?.error?.message ??
      err.response?.data?.message ??
      err.message
    )
  }
  return 'Unexpected error'
}
