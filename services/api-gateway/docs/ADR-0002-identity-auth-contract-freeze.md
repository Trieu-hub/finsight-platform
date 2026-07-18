# ADR-0002 — Identity & Auth Contract Freeze (Phase 2 enablement)

- Status: **Accepted / Frozen**
- Date: 2026-06-06
- Scope: FinSight API Gateway — the JWT validation, public-route, error-code,
  identity-header, and correlation-header contract the gateway will **enforce** when
  edge authentication is enabled (Phase 2+).
- Supersedes: **ADR-0001 §4, §5, §6 (in part)** — see §9 "Relationship to ADR-0001".
  All other ADR-0001 sections (rate limiting, correlation client-hint handling,
  implementation tech decision, the V1 removability invariant) remain in force.
- Superseded by: **[ADR-0005](ADR-0005-rs256-asymmetric-jwt-signing.md) — §1 (JWT Algorithm)
  only** (HS512 → RS256, 2026-07-18). §1a–§9 remain in force.

This ADR **locks** the values below. Each was verified against the running
`auth-service` source (`JwtService`, `JwtProperties`, `application.yml`, `SecurityConfig`,
`RoleName`) and the platform `docker-compose.yml`. **Changing any locked value requires
a new ADR, not an edit to this file.**

Governing invariant (unchanged from ADR-0001): the gateway validates but does **not**
replace downstream validation; every service keeps validating tokens locally; injected
identity headers are informational only and **never** trusted for authorization; the
gateway remains removable without service changes.

---

## 1. JWT Algorithm — **HS512 (HMAC-SHA-512)**

> **Superseded by [ADR-0005](ADR-0005-rs256-asymmetric-jwt-signing.md) (2026-07-18).** The
> platform signs **RS256** with an asymmetric keypair; this section and its `JWT_SECRET ≥ 512
> bits` constraint are **historical**. Read §1a and ADR-0005 for the live contract.

- `auth-service` signs with `Keys.hmacShaKeyFor(secret)` + `signWith(key)` (jjwt 0.12.6),
  which auto-selects the HMAC variant from the key length. The shared `JWT_SECRET` is
  ≥ 512 bits, so the emitted `alg` is **HS512** (verified: token header
  `{"alg":"HS512"}`).
- **Locked:** the gateway pins **HS512** on verification and **rejects any other `alg`**
  — explicitly `none` and any asymmetric `RSxxx`/`ESxxx` — to prevent algorithm-confusion
  attacks.
- **Constraint (part of the contract):** `JWT_SECRET` MUST remain **≥ 512 bits**. A
  shorter secret silently downgrades the issuer to HS256 and would break gateway
  validation.

## 1a. Key identification (`kid`) and rotation — **JWK Set discovery**

Where §1 concerns *how* a token is signed, this pins *which key* signed it — the thing that
makes a key replaceable at all.

> ⚠️ **§1 above is stale and contradicts this section.** It describes the pre-migration
> HS512 shared secret and states that asymmetric `alg`s are rejected; the platform has signed
> **RS256** with an asymmetric keypair since the RS256 migration, which is what the gateway
> and all seven validators actually pin, and what this section builds on. Correcting §1
> requires a **new ADR** by this document's own rule (a locked value cannot be edited here).
> That ADR is now **[ADR-0005](ADR-0005-rs256-asymmetric-jwt-signing.md)**, which supersedes §1
> (HS512 → RS256) and closes what was previously an open documentation gap.

- Every issued token carries a **`kid` header**. It is the key's **RFC 7638 JWK thumbprint** —
  a hash of the key material — **not** an assigned name. Issuer and validator therefore derive
  the same id for the same key independently, with no shared configuration and nothing to bump
  or mistype.
- auth-service publishes the accepted public keys as a **JWK Set (RFC 7517)** at
  **`/.well-known/jwks.json`**, unauthenticated. Requiring a token there would be circular: its
  callers are the components deciding whether a token is good. It carries public keys only.
- Validators resolve the key by `kid` from that document, caching it (5-minute TTL, refreshed
  early on an unseen `kid`). This is **not** a per-request call to auth-service — the standing
  rule that no service calls auth-service to validate a token is unchanged.

**Locked:** `kid` is present on every issued token; validators MUST select the key by it and
MUST reject a token whose `kid` is not in the set. This is **additive** to §1–§3 — `alg`, `iss`
and `aud` are untouched, and a validator pinned to a single key still verifies these tokens,
because an unknown header member is ignored. That is what allowed the rollout to be safe.

**Why per-key discovery rather than a static key list:** a static list makes rotation a
lockstep restart of all eight components — the same coordinated-cutover problem RS256 was
supposed to end. With discovery, only auth-service restarts.

**Rotation contract:** during a rotation the set advertises **two** keys — the incoming one
(signs and verifies) and the outgoing one (verifies only) — for one access-token lifetime, so
tokens minted seconds before the switch are not mass-invalidated. `JWT_PREVIOUS_PUBLIC_KEYS`
holds the outgoing key for that window and is empty at steady state. Procedure:
[docs/security/jwt-key-rotation.md](../../../docs/security/jwt-key-rotation.md).

**Failure posture:** a validator that cannot reach the JWK Set keeps its last known good keys
(falling back to the configured `JWT_PUBLIC_KEY` if it never fetched one), rather than
rejecting everything. auth-service being down must not make every other service unauthenticable.
The cost — a rotation cannot propagate during that outage — is visible on
`finsight.jwks.refresh.failed`.

**Scope note:** the JWK Set is served on auth-service's own port for the platform's internal
validators. It is deliberately **not** routed through the gateway (§4 is unchanged), because
nothing outside the platform consumes FinSight tokens today. Exposing it publicly would be a
routing addition, not a contract change.

## 2. JWT Issuer (`iss`) — **`finsight-auth`**

- Source: `jwt.issuer` (env `JWT_ISSUER`, default `finsight-auth`). Emitted today,
  not yet enforced by any service.
- **Locked:** the gateway enforces `iss == finsight-auth`. Mismatch/absent →
  `401 TOKEN_INVALID`.

## 3. JWT Audience (`aud`) — **`finsight-api`**

- Source: `jwt.audience` (env `JWT_AUDIENCE`, default `finsight-api`). Emitted today,
  not yet enforced.
- **Locked:** the gateway enforces that the token `aud` set **contains** `finsight-api`.
  Missing/mismatch → `401 TOKEN_INVALID`.

> Enforcement of §1–§3 activates in **Phase 2**. Phase 1 forwards blindly. Values are
> frozen now so Phase 2 needs no rediscovery.

## 4. Public Routes (no access-token required)

Deny-by-default. Only these bypass gateway authentication. Derived from auth-service's
own `SecurityConfig` `permitAll` set plus gateway-local actuator.

| Method | Path | Reason |
|--------|------|--------|
| POST | `/api/v1/auth/register` | caller has no token yet |
| POST | `/api/v1/auth/login` | caller has no token yet |
| POST | `/api/v1/auth/refresh` | carries a **refresh** token, not the access JWT; access token may be expired |
| POST | `/api/v1/auth/logout` | carries a **refresh** token; must work with an expired access token |
| GET | `/actuator/health` | gateway liveness (served locally, never proxied) |
| GET | `/actuator/info` | gateway info (served locally, never proxied) |

Everything else requires a valid token from Phase 2 onward. In particular
`GET /api/v1/auth/me`, and all `/api/v1/users/**`, `/api/v1/transactions/**`,
`/api/v1/budgets/**` are **authenticated** (not public).

## 5. Gateway Error Codes

Envelope identical to the services: `{ "success": false, "error": { "code", "message" } }`.
Gateway codes are edge-namespaced and never collide with domain codes
(`BUDGET_NOT_FOUND`, `VALIDATION_ERROR`, …), which are passed through untouched.

| HTTP | code | Meaning | Active from |
|------|------|---------|-------------|
| 401 | `UNAUTHENTICATED` | no bearer token / malformed `Authorization` header | Phase 2 |
| 401 | `TOKEN_INVALID` | bad signature, wrong `alg`, failed `iss`/`aud`, malformed claims | Phase 2 |
| 401 | `TOKEN_EXPIRED` | signature valid but `exp` is in the past | Phase 2 |
| 401 | `TOKEN_REVOKED` | signature valid and unexpired, but revoked (logout / ban / role change) | **live** |
| 404 | `ROUTE_NOT_FOUND` | no route prefix matched | **Phase 1 (live)** |
| 413 | `PAYLOAD_TOO_LARGE` | request body over `gateway.limits.max-body-bytes` | **live** |
| 503 | `SERVICE_UNAVAILABLE` | downstream unreachable (connection refused) | **Phase 1 (live)** |
| 504 | `SERVICE_TIMEOUT` | downstream read timeout | **Phase 1 (live)** |

Notes:
- **`TOKEN_EXPIRED` is a distinct code** from `TOKEN_INVALID` (expiry is the common,
  client-actionable case → triggers a token refresh; other failures are not). This is
  the deliberate refinement of ADR-0001, which had folded expiry into `TOKEN_INVALID`.
- **`TOKEN_REVOKED` is now live**, reversing this ADR's original deferral to V2. It is
  distinct from `TOKEN_EXPIRED` because the client action differs: expiry is refreshable,
  revocation is not — the client must re-authenticate. See §5a.
- This table is the **authentication + routing** code set. `RATE_LIMITED` (429, Phase 5)
  remains frozen under **ADR-0001 §5** and is unaffected.

## 5a. Access-token revocation

A signed JWT proves only that auth-service minted it — not that it is still meant to work.
Until this was added, logout and admin ban/role-change revoked the *refresh* token only, so
the access token already in the user's hands kept working for the rest of its TTL (15 min).

- **auth-service writes** a per-user cutoff to Redis on logout, ban, role change and delete:
  `revoked:user:{userId} -> cutoff` (epoch **seconds**), TTL = the access-token lifetime.
- **api-gateway reads** it and rejects any token with `iat < cutoff` → `401 TOKEN_REVOKED`.

Design points, deliberate:

- **Keyed per user, not per token.** Logout, ban and role change all mean "none of this
  user's tokens are good any more", so one key kills every outstanding token at once. No
  `jti`, and therefore **no change to the token contract** — §1–§3 are untouched and
  downstream services need no change, preserving the invariant that the gateway is removable.
- **The cutoff is rounded up to the next second.** `iat` has one-second resolution, so a
  token minted earlier in the same second as the revocation would otherwise survive. Rounding
  up errs toward revoking a second too much: the worst case is a user who logs in within the
  same second as logging out and must log in again, versus a revoked token staying valid for
  its full lifetime.
- **Enforced at the gateway only**, because it is the single entry point and downstream
  services are not exposed. This is the same additive-edge-check posture as §5's other codes.
- **The check fails open.** If Redis is unreachable the request proceeds, and the failure is
  logged and counted (`finsight.gateway.revocation.check.failed`, alertable). Revocation is a
  second layer over an already-verified, short-lived token, so a Redis outage must not take
  the API down; the accepted cost is that revocation does not bite while Redis is down.
  Consequently Redis is kept **out of the gateway's readiness probe** — a gateway that serves
  correctly on the fail-open path must not report itself unready.

## 6. Identity Headers (gateway-injected, informational only)

Injected by the gateway from the validated token; **never** authoritative. The gateway
**strips any inbound copies** before injecting its own (prevents client spoofing).

| Header | Source claim | Value format |
|--------|--------------|--------------|
| `X-Authenticated-User-Id` | `userId` | numeric (BIGINT) |
| `X-Authenticated-Role` | `role` | one of `ROLE_USER`, `ROLE_ADMIN`, `ROLE_ANALYST` (verified against `RoleName`) |

Injected from Phase 4. No service may read these for authorization (governing invariant).

## 7. Correlation Header — **`X-Request-Id`**

| Header | Direction | Rule |
|--------|-----------|------|
| `X-Request-Id` | gateway → downstream **and** client response | **Canonical.** Gateway-generated **UUIDv4**, lowercase, hyphenated (36 chars). Authoritative; the gateway always generates its own even if the client sent one. |

Untrusted inbound client-supplied correlation hints are handled per **ADR-0001 §7**
(`X-Client-Request-Id`, recorded separately, never promoted). Active from Phase 3.

---

## 8. Activation timeline (summary)

| Contract | Frozen (this ADR) | Enforced/active |
|----------|-------------------|-----------------|
| §1 alg pin, §2 iss, §3 aud | now | Phase 2 |
| §1a `kid` + JWK Set discovery / key rotation | — | **live** |
| §4 public routes | now | Phase 2 (deny-by-default auth) |
| §5 auth error codes (`UNAUTHENTICATED`, `TOKEN_INVALID`, `TOKEN_EXPIRED`) | now | Phase 2 |
| §5 routing error codes (`ROUTE_NOT_FOUND`, `SERVICE_UNAVAILABLE`, `SERVICE_TIMEOUT`) | — | **live (Phase 1)** |
| §5a `TOKEN_REVOKED` + revocation denylist | — | **live** (was deferred to V2) |
| §6 identity headers | now | Phase 4 |
| §7 `X-Request-Id` | now | Phase 3 |

## 9. Relationship to ADR-0001

This ADR refines the auth-facing parts of ADR-0001 with values confirmed against source
on 2026-06-06:

1. **§5 error codes — adds `TOKEN_EXPIRED`** as a code distinct from `TOKEN_INVALID`
   (ADR-0001 had merged them). Supersedes ADR-0001 §5 for the auth codes; the 429/routing
   rows and the `TOKEN_REVOKED` exclusion are unchanged.
2. **§4 public routes — `POST /api/v1/auth/logout` is public** (it carries a refresh
   token and must succeed with an expired access token, exactly like `refresh`). ADR-0001
   §4 had marked logout authenticated; this corrects that inconsistency. Public actuator
   paths stated as the actual `/actuator/health` + `/actuator/info`.
3. **§6 identity headers — role values corrected** to `ROLE_USER`/`ROLE_ADMIN`/`ROLE_ANALYST`
   (ADR-0001 listed `USER`/`PREMIUM`/`ADMIN`, which do not match the `RoleName` enum or
   the emitted `role` claim).

Unchanged and still governed by ADR-0001: the V1 routing-only design and removability
invariant, rate-limit settings (§8), correlation client-hint handling (§7), and the
Phase-1 implementation tech decision.

> **Verification provenance:** `auth-service/.../JwtService.java` (claims, signing),
> `JwtProperties.java` + `application.yml` (`jwt.issuer=finsight-auth`,
> `jwt.audience=finsight-api`), `auth-service/.../config/SecurityConfig.java` (permitAll
> set), `enums/RoleName.java` (role values), `docker-compose.yml` (shared `JWT_SECRET`
> ≥ 512 bits). Algorithm confirmed empirically from an issued token header `{"alg":"HS512"}`.
