# ADR-0002 — Identity & Auth Contract Freeze (Phase 2 enablement)

- Status: **Accepted / Frozen**
- Date: 2026-06-06
- Scope: FinSight API Gateway — the JWT validation, public-route, error-code,
  identity-header, and correlation-header contract the gateway will **enforce** when
  edge authentication is enabled (Phase 2+).
- Supersedes: **ADR-0001 §4, §5, §6 (in part)** — see §9 "Relationship to ADR-0001".
  All other ADR-0001 sections (rate limiting, correlation client-hint handling,
  implementation tech decision, the V1 removability invariant) remain in force.

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
| 404 | `ROUTE_NOT_FOUND` | no route prefix matched | **Phase 1 (live)** |
| 503 | `SERVICE_UNAVAILABLE` | downstream unreachable (connection refused) | **Phase 1 (live)** |
| 504 | `SERVICE_TIMEOUT` | downstream read timeout | **Phase 1 (live)** |

Notes:
- **`TOKEN_EXPIRED` is a distinct code** from `TOKEN_INVALID` (expiry is the common,
  client-actionable case → triggers a token refresh; other failures are not). This is
  the deliberate refinement of ADR-0001, which had folded expiry into `TOKEN_INVALID`.
- This table is the **authentication + routing** code set. `RATE_LIMITED` (429, Phase 5)
  remains frozen under **ADR-0001 §5** and is unaffected. `TOKEN_REVOKED` remains
  intentionally absent (deferred to V2).

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
| §4 public routes | now | Phase 2 (deny-by-default auth) |
| §5 auth error codes (`UNAUTHENTICATED`, `TOKEN_INVALID`, `TOKEN_EXPIRED`) | now | Phase 2 |
| §5 routing error codes (`ROUTE_NOT_FOUND`, `SERVICE_UNAVAILABLE`, `SERVICE_TIMEOUT`) | — | **live (Phase 1)** |
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
