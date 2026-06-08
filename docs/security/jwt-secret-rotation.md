# JWT Secret Rotation Runbook

FinSight uses a **single symmetric HMAC secret** (`JWT_SECRET`) shared by all services:
`auth-service` signs tokens with it; every other service validates with the same value.
There is intentionally (for now) **no support for validating two secrets at once** — that
would require a code change we are deferring along with the RS256 migration. Rotation is
therefore a **coordinated cutover**, not a gradual roll.

## When to rotate
- **Immediately / one-time:** the original secret
  (`finSight-auth-service-dev-secret-key-...`) was committed to git and must be treated as
  compromised. The fresh secret now in `.env` already replaces it for local use; rotate any
  shared/deployed environment that ever received the old value.
- **Routinely:** on a schedule (e.g. quarterly) and on any suspected leak or operator
  offboarding.

## Impact of a rotation (know this before you start)
- **Access tokens** (15 min TTL) signed with the old secret become invalid the instant a
  service starts using the new secret → those requests get `401`.
- **Refresh tokens** are opaque values stored in Redis, **not** HMAC-signed, so they
  survive rotation. Clients holding a valid refresh token recover automatically: their next
  `POST /api/v1/auth/refresh` mints a new access token under the new secret. No user needs
  to re-enter a password.
- Net effect: a sub-15-minute window where some in-flight access tokens 401 and clients
  transparently refresh. Acceptable for this platform; schedule off-peak regardless.

## Procedure (local / Docker Compose)
1. **Generate** a new secret (>= 256 bits):
   ```bash
   openssl rand -base64 64 | tr -d '\n'
   ```
2. **Update `.env`** — replace the `JWT_SECRET=` value. Do not edit `docker-compose.yml`;
   it reads from `.env`.
3. **Restart every service that holds the secret, together** (all six — auth, user,
   transaction, budget, dashboard, gateway). They must not run a mix of old/new:
   ```bash
   docker compose up -d --force-recreate \
     auth-service user-service transaction-service budget-service dashboard-service api-gateway
   ```
4. **Verify:** a fresh `login` issues a working token; an old token now returns `401`;
   a `refresh` with a pre-rotation refresh token succeeds.

## Procedure (orchestrated env, e.g. Kubernetes — future)
1. Update the `JWT_SECRET` Secret object.
2. Trigger a rolling restart of **all** services. Because validation is single-secret,
   briefly during the roll some pods sign/validate with the new secret while others still
   use the old one → cross-pod 401s until the roll completes. Keep the roll fast, or take a
   short maintenance window. (Eliminating this window is the motivation for the planned
   RS256 + dual-key-validation work — out of scope here.)

## Hard rules
- The secret lives **only** in `.env` (local) or the platform secret store (deployed).
  Never in `docker-compose.yml`, `application.yml`, or any committed file.
- All six services must always share the **same** value at steady state.
- After rotating away from a compromised secret, confirm the old value appears in **no**
  running environment before considering the incident closed.
