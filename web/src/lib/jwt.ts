// Minimal client-side JWT payload decode — JUST to read the `role` claim for UI
// (show/hide the Admin menu). This is NOT verification: the signature is not checked
// and must never be trusted for security. Authorization is enforced server-side.

export interface JwtPayload {
  userId?: number
  email?: string
  role?: string
  sub?: string
  exp?: number
}

export function decodeJwt(token: string | null): JwtPayload | null {
  if (!token) return null
  try {
    const payload = token.split('.')[1]
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    return JSON.parse(json) as JwtPayload
  } catch {
    return null
  }
}

export function roleFromToken(token: string | null): string | null {
  return decodeJwt(token)?.role ?? null
}
