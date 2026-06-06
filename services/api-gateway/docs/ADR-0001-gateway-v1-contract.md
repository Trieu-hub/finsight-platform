# ADR-0001 — Gateway V1 Contract Freeze

- Status: **Accepted / Frozen** (Phase 0.5 — ADR Freeze)
- Date: 2026-06-06
- Scope: FinSight API Gateway **V1** (routing-first; security additive in later phases)
- Supersedes: none

This ADR **locks** the values that the gateway and the platform must agree on
before implementation proceeds. Values here are verified against the running
`auth-service` token issuer (`JwtService` / `application.yml`) and the platform
`docker-compose.yml`. Changing any locked value is a new ADR, not an edit.

The governing V1 invariant (ratified earlier) still holds: the gateway validates
but does **not** replace downstream validation; services keep validating; injected
identity headers are informational only; no service trusts them for authorization;
the gateway is removable without service changes.

---

## 1. JWT Algorithm — **HS512 (HMAC-SHA-512)**

- `auth-service` signs with `Keys.hmacShaKeyFor(secret)` + `signWith(key)` (jjwt
  0.12.6), which **auto-selects the HMAC variant from the key length**. The shared
  dev secret is 512 bits, so the emitted algorithm is **HS512**.
- **Locked decision:** the gateway pins **HS512** and rejects any other `alg`
  (notably `none` and any RSxxx) to prevent algorithm-confusion attacks.
- **Constraint:** the shared `JWT_SECRET` MUST remain **≥ 512 bits** so the issuer
  keeps emitting HS512. A shorter secret would silently downgrade the issuer to
  HS256 and break gateway validation. This constraint is part of the contract.
- **V2 note:** RS256/JWKS migration is out of scope; when it lands, the pinned
  algorithm becomes the JWKS-advertised one and this section is superseded.

## 2. JWT Issuer — **`finsight-auth`**

- Source: `jwt.issuer` (`JWT_ISSUER`, default `finsight-auth`). Emitted today,
  **not enforced** by any service.
- **Locked decision:** the gateway **enforces** `iss == finsight-auth`. Mismatch →
  `401 TOKEN_INVALID`.
- **Pre-flight (R2):** confirmed the issuer the gateway will enforce equals the
  issuer auth-service mints. Verified identical.

## 3. JWT Audience — **`finsight-api`**

- Source: `jwt.audience` (`JWT_AUDIENCE`, default `finsight-api`). Emitted today,
  **not enforced**.
- **Locked decision:** the gateway **enforces** that the token `aud` set contains
  `finsight-api`. Missing/mismatch → `401 TOKEN_INVALID`.
- **Pre-flight (R2):** verified gateway expectation equals minted value.

> Enforcement of §2/§3 is **activated in Phase 2**, not Phase 1. Phase 1 forwards
> blindly. The values are frozen now so Phase 2 needs no rediscovery.

## 4. Public Routes (no token required)

Deny-by-default. Only these bypass authentication:

| Method | Path | Reason |
|--------|------|--------|
| POST | `/api/v1/auth/register` | caller has no token yet |
| POST | `/api/v1/auth/login` | caller has no token yet |
| POST | `/api/v1/auth/refresh` | carries a **refresh** token, not the access JWT — gateway must NOT validate the access JWT here |
| GET | `/health` | gateway liveness, unauthenticated |

Everything else under `/api/v1/**` requires a valid token (from Phase 2 onward).
`POST /api/v1/auth/logout` and `GET /api/v1/auth/me` are **authenticated** (not public).

Route → target table (path forwarded unchanged, no rewrite):

| Public prefix | Target (compose DNS) |
|---------------|----------------------|
| `/api/v1/auth/**` | `http://auth-service:8081` |
| `/api/v1/users/**` | `http://user-service:8082` |
| `/api/v1/transactions/**` | `http://transaction-service:8083` |
| `/api/v1/budgets/**` | `http://budget-service:8084` |

## 5. Gateway Error Codes

Same envelope as the services: `{ "success": false, "error": { "code", "message" } }`.
Gateway codes are edge-namespaced and never collide with domain codes
(`BUDGET_NOT_FOUND`, `VALIDATION_ERROR`, …), which are **passed through untouched**.

| HTTP | code | Meaning | Active from |
|------|------|---------|-------------|
| 401 | `UNAUTHENTICATED` | missing/malformed bearer token | Phase 2 |
| 401 | `TOKEN_INVALID` | bad signature / expired / iss / aud / alg | Phase 2 |
| 429 | `RATE_LIMITED` | per-IP or per-user limit exceeded | Phase 5 |
| 404 | `ROUTE_NOT_FOUND` | no route prefix matched | **Phase 1** |
| 503 | `SERVICE_UNAVAILABLE` | downstream unreachable | **Phase 1** |
| 504 | `SERVICE_TIMEOUT` | downstream read timeout | **Phase 1** |

`TOKEN_REVOKED` is intentionally **absent** — access-token revocation does not exist
upstream and is deferred to V2 (see review R1).

## 6. Identity Header Names (informational only)

Injected by the gateway from the validated token; **never** authoritative in V1.
The gateway strips any inbound copies before injecting its own.

| Header | Value |
|--------|-------|
| `X-Authenticated-User-Id` | `userId` claim (numeric/BIGINT) |
| `X-Authenticated-Role` | `role` claim (`USER`/`PREMIUM`/`ADMIN`) |

Injected from **Phase 4**. No service may read these for authorization (clause 4).

## 7. Correlation ID Format

| Header | Direction | Rule |
|--------|-----------|------|
| `X-Request-Id` | gateway → downstream & client response | **Canonical.** Gateway-generated **UUIDv4**, lowercase, hyphenated (36 chars). Authoritative. |
| `X-Client-Request-Id` | client → gateway, recorded only | Untrusted client hint. Accepted only if it matches `^[A-Za-z0-9._-]{8,128}$`; recorded in logs as a **separate** field; **never** promoted to the canonical ID. |

Gateway always generates its own `X-Request-Id` even when a client hint is present.
Active from **Phase 3**.

## 8. Default Rate-Limit Settings

Backed by Redis, **fail-open**, tunable via config without redeploy. Active from
**Phase 5** (values frozen now).

| Scope | Default | Stage |
|-------|---------|-------|
| Per-IP (pre-auth) | 100 req/s, burst 200 | before auth |
| Per-user (post-auth, keyed on `userId`) | 60 req/min, sliding window | after auth |

Failure mode: Redis unavailable → both limiters fail-open; **gateway readiness must
not depend on Redis**. Optional in-process per-instance fallback floor permitted.

---

## Implementation tech decision (Phase 1)

- **Decision:** V1 gateway is a **minimal Spring Boot 4 servlet reverse proxy** using
  only Boot-managed dependencies (`spring-boot-starter-webmvc` + `actuator`).
- **Why:** Spring Cloud Gateway has no Spring Boot 4.0-compatible release train at
  this time; adopting it would block the gateway on an external version. A small
  Boot-native proxy guarantees the build resolves and fully satisfies Phase 1's
  routing-only scope.
- **Consequence:** revisit at the Phase 5/V2 boundary — if a Boot 4-compatible
  Spring Cloud Gateway becomes available, migrating is an internal gateway change
  that does not affect any downstream service (the V1 removability invariant holds).
