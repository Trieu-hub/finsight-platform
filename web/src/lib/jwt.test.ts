import { describe, it, expect } from 'vitest'
import { decodeJwt, roleFromToken, type JwtPayload } from './jwt'

// Build a JWT-shaped string with a base64url-encoded payload (no signature verification — this
// helper mirrors how auth-service encodes claims). Padding is stripped and +/ mapped to -/_,
// exactly the base64url form decodeJwt must reverse.
function makeToken(payload: JwtPayload): string {
  const b64 = btoa(JSON.stringify(payload))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
  return `header.${b64}.signature`
}

describe('decodeJwt', () => {
  it('decodes the payload claims', () => {
    const token = makeToken({ userId: 7, email: 'a@b.com', role: 'ROLE_ADMIN', exp: 123 })
    expect(decodeJwt(token)).toEqual({ userId: 7, email: 'a@b.com', role: 'ROLE_ADMIN', exp: 123 })
  })

  it('handles base64url payloads containing - and _ (bytes that map to +/ in standard base64)', () => {
    // 0xFB 0xFF -> standard base64 "+/"; base64url makes that "-_", which decodeJwt must map back.
    const token = makeToken({ email: 'ûÿ', role: 'ROLE_USER' })
    expect(decodeJwt(token)?.role).toBe('ROLE_USER')
  })

  it('returns null for a null token', () => {
    expect(decodeJwt(null)).toBeNull()
  })

  it('returns null for a malformed token (no dot / bad base64)', () => {
    expect(decodeJwt('garbage')).toBeNull()
    expect(decodeJwt('not.a.jwt')).toBeNull()
    expect(decodeJwt('')).toBeNull()
  })
})

describe('roleFromToken', () => {
  it('extracts the role claim', () => {
    expect(roleFromToken(makeToken({ role: 'ROLE_ADMIN' }))).toBe('ROLE_ADMIN')
  })

  it('returns null when there is no role or no token', () => {
    expect(roleFromToken(makeToken({ email: 'a@b.com' }))).toBeNull()
    expect(roleFromToken(null)).toBeNull()
    expect(roleFromToken('garbage')).toBeNull()
  })
})
