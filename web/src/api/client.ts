import axios from 'axios'

const ACCESS_KEY = 'finsight_token'
const REFRESH_KEY = 'finsight_refresh'

export const tokenStore = {
  getAccess: () => localStorage.getItem(ACCESS_KEY),
  getRefresh: () => localStorage.getItem(REFRESH_KEY),
  // Store the access token, and the refresh token when a fresh one is provided.
  set: (access: string, refresh?: string) => {
    localStorage.setItem(ACCESS_KEY, access)
    if (refresh) localStorage.setItem(REFRESH_KEY, refresh)
  },
  clear: () => {
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
  },
}

// One axios instance for the whole app. baseURL is "/api/v1" — a relative path, so
// the browser hits the Vite dev server, which proxies to the gateway (see vite.config.ts).
export const api = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
})

// REQUEST interceptor: attach the Bearer token to every outgoing request.
api.interceptors.request.use((config) => {
  const token = tokenStore.getAccess()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// --- Silent refresh -------------------------------------------------------------
// The access token lives 15 min; the refresh token lives 7 days. When a request
// fails with 401 (access expired), we exchange the refresh token for a new access
// token and replay the original request, so the user is not kicked to /login.

// Single-flight: if several requests 401 at once, refresh only ONCE and let them
// all await the same promise (avoids a burst of /auth/refresh calls).
let refreshPromise: Promise<string> | null = null

async function refreshAccessToken(): Promise<string> {
  const refreshToken = tokenStore.getRefresh()
  if (!refreshToken) throw new Error('No refresh token')
  // Use bare axios (not `api`) so this call skips the interceptors — no recursion,
  // no stale Authorization header on a public endpoint.
  const { data } = await axios.post('/api/v1/auth/refresh', { refreshToken })
  if (!data?.accessToken) throw new Error('Refresh did not return an access token')
  tokenStore.set(data.accessToken, data.refreshToken)
  return data.accessToken
}

function redirectToLogin() {
  tokenStore.clear()
  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
  }
}

// RESPONSE interceptor.
api.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config
    const status = error.response?.status
    const isRefreshCall = original?.url?.includes('/auth/refresh')

    // Try a silent refresh once per request. Skip if it's the refresh call itself
    // (its 401 means the refresh token is dead) or we already retried.
    if (status === 401 && original && !original._retry && !isRefreshCall) {
      original._retry = true
      try {
        if (!refreshPromise) {
          refreshPromise = refreshAccessToken().finally(() => {
            refreshPromise = null
          })
        }
        const newToken = await refreshPromise
        original.headers.Authorization = `Bearer ${newToken}`
        return api(original) // replay the original request
      } catch {
        redirectToLogin()
        return Promise.reject(error)
      }
    }

    if (status === 401) redirectToLogin()
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
