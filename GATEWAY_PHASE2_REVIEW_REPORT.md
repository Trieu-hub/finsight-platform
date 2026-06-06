# Gateway Phase 2 — Implementation & Validation Review

**Date:** 2026-06-06
**Scope delivered:** edge JWT authentication on the API Gateway — JWT validation,
issuer/audience enforcement, public-route allow-list, the gateway authentication error
contract, and Authorization-header forwarding. Frozen contract: **ADR-0002**.
**Outcome:** ✅ Implemented, unit-tested (12/12), and validated end-to-end through `:8080`.
**Explicitly out of scope and NOT implemented:** correlation IDs, identity headers, rate
limiting, observability enhancements, perimeter cutover.

---

## 1. What was implemented

The gateway remains a minimal Spring Boot servlet reverse proxy; Phase 2 adds an
authentication gate *before* forwarding. The governing invariant holds: the gateway
validates but does **not** replace downstream validation — every service still validates
the token itself, and the bearer token is forwarded unchanged.

| # | File | Change |
|---|------|--------|
| 1 | `api-gateway/pom.xml` | Added jjwt 0.12.6 (`jjwt-api`, `jjwt-impl`, `jjwt-jackson`) — same stack/version as the services. |
| 2 | `config/JwtProperties.java` (new) | Binds `jwt.secret` / `jwt.issuer` (default `finsight-auth`) / `jwt.audience` (default `finsight-api`). |
| 3 | `security/JwtAuthenticator.java` (new) | Verifies signature, **pins HS512** (rejects any other `alg`, incl. `none` and HS256-with-same-secret), enforces `iss` and `aud`. Returns a typed `Outcome` (`AUTHENTICATED` / `MISSING` / `EXPIRED` / `INVALID`). |
| 4 | `config/GatewayProperties.java` | Added `publicRoutes` (method+path) allow-list. |
| 5 | `proxy/GatewayProxyController.java` | After route resolution, enforces auth on every non-public route and maps failures to the error contract; the `Authorization` header continues to be forwarded downstream (it is not a hop-by-hop header). |
| 6 | `resources/application.yml` | Added `jwt.*` and the frozen `gateway.public-routes` (register/login/refresh/logout). |
| 7 | `ApiGatewayApplication.java` | Registered `JwtProperties`; updated class javadoc to Phase 2. |
| 8 | `docker-compose.yml` | Gateway now receives `JWT_SECRET` (shared anchor); updated the gateway comment. |
| 9 | tests | New `support/JwtTestTokens`, new `GatewayAuthTest` (10 cases), updated `GatewayRoutingTest` (protected route now needs a token). Added the Maven wrapper to the module (it had none). |

### Design decisions / notes
- **Algorithm pin is explicit.** `verifyWith(secret)` alone would accept an HS256/HS384
  token signed with the same secret, so the validator additionally checks the JWS header
  `alg == HS512` (ADR-0002 §1). Verified live: an HS256 token with the correct secret →
  `TOKEN_INVALID`.
- **Route resolution precedes auth.** An unknown prefix returns `404 ROUTE_NOT_FOUND`
  (Phase 1 behaviour preserved) before any token check; known routes then authenticate.
- **Public allow-list is exact method+path** (not prefix), so `POST /api/v1/auth/login`
  is public while `GET /api/v1/auth/me` is protected — matching auth-service's own
  `SecurityConfig` and ADR-0002 §4.
- **`logout` is public** (carries a refresh token; must work with an expired access
  token), per ADR-0002 §4.

---

## 2. Unit tests — `mvnw test` (api-gateway)

```
Tests run: 12, Failures: 0, Errors: 0  — BUILD SUCCESS
  GatewayAuthTest      : 10
  GatewayRoutingTest   : 2
```
`GatewayAuthTest` covers: no token → `UNAUTHENTICATED`; malformed header →
`UNAUTHENTICATED`; expired → `TOKEN_EXPIRED`; bad signature / wrong alg (HS256) / wrong
issuer / wrong audience → `TOKEN_INVALID`; valid token → reaches forward (503 dead
backend); public `login` without token → reaches forward (503); `auth/me` without token →
`UNAUTHENTICATED`. The "reaches forward → 503" technique proves the auth gate let the
request through, independent of any running backend.

## 3. End-to-end validation through `:8080` (full compose stack)

All four services + gateway healthy. Tokens minted with the real shared secret.

**Edge auth on a protected route (`GET /api/v1/budgets`):**

| Token | HTTP | error.code |
|-------|------|-----------|
| none | 401 | `UNAUTHENTICATED` |
| valid HS512 (correct iss/aud) | **200** | — (forwarded; downstream also validated) |
| expired | 401 | `TOKEN_EXPIRED` |
| HS256 w/ same secret (wrong alg) | 401 | `TOKEN_INVALID` |
| wrong issuer | 401 | `TOKEN_INVALID` |
| wrong audience | 401 | `TOKEN_INVALID` |
| garbage | 401 | `TOKEN_INVALID` |

**Public-route bypass & real token chain:**

| Request | HTTP | Meaning |
|---------|------|---------|
| `POST /api/v1/auth/register` (no token) | 200 | public — bypasses auth |
| `POST /api/v1/auth/login` (no token) | 200 | public — bypasses auth |
| `GET /api/v1/budgets` (real login token) | 200 | validated at edge + forwarded |
| `GET /api/v1/transactions` (real token) | 200 | validated + forwarded |
| `GET /api/v1/users/me` (real token) | 404 | auth passed; no profile yet |
| `GET /api/v1/auth/me` (real token) | 200 | authenticated route works |
| `GET /api/v1/auth/me` (no token) | 401 | `UNAUTHENTICATED` — correctly NOT public |

**Authorization-header forwarding:** confirmed — protected calls returned downstream
`200`s, which the services produce only after validating the *same* forwarded token
(the gateway does not strip or replace it).

## 4. Scope adherence — NOT implemented (by instruction)

| Item | Status |
|------|--------|
| Correlation IDs (`X-Request-Id`) | not added (frozen for Phase 3) |
| Identity headers (`X-Authenticated-*`) | not added (frozen for Phase 4) |
| Rate limiting (`RATE_LIMITED`) | not added (frozen for Phase 5) |
| Observability enhancements | not added |
| Perimeter cutover (un-publishing backend ports) | not done — backends still publish 8081–8084 and still validate tokens |

No downstream service code was changed. The gateway remains removable without service
changes (the V1 invariant in ADR-0001).

## 5. Error-code contract status (after Phase 2)

| code | HTTP | Source | State |
|------|------|--------|-------|
| `UNAUTHENTICATED` | 401 | gateway | **live (Phase 2)** |
| `TOKEN_INVALID` | 401 | gateway | **live (Phase 2)** |
| `TOKEN_EXPIRED` | 401 | gateway | **live (Phase 2)** |
| `ROUTE_NOT_FOUND` | 404 | gateway | live (Phase 1) |
| `SERVICE_UNAVAILABLE` | 503 | gateway | live (Phase 1) |
| `SERVICE_TIMEOUT` | 504 | gateway | live (Phase 1) |
| `RATE_LIMITED` | 429 | gateway | reserved (Phase 5) — unchanged |
| `TOKEN_REVOKED` | — | — | reserved (future) — unchanged |

## 6. Observations (non-blocking, not addressed here)

- **`refresh` token semantics:** `/api/v1/auth/refresh` is public, so the gateway does
  not (and must not) validate the access JWT there — correct. Access-token revocation is
  not enforced at the edge (no `TOKEN_REVOKED`), as frozen.
- **Pre-existing, unrelated:** user-service still maps a malformed JSON body to `500`
  rather than `400` (documented in the consolidation report). The gateway relays it
  faithfully; out of scope here.
- **Maven wrapper** was missing from `api-gateway`; it was added (mvnw + properties) to
  match the other modules and enable `mvnw test`.

## 7. Conclusion

Gateway Phase 2 is **complete and validated**: the gateway authenticates every non-public
request at the edge (signature, HS512, issuer, audience), returns the frozen
`UNAUTHENTICATED` / `TOKEN_INVALID` / `TOKEN_EXPIRED` contract, honours the public-route
allow-list, and forwards the Authorization header so downstream validation still applies.
Implementation stayed strictly within scope; correlation IDs, identity headers, rate
limiting, observability, and the perimeter cutover were deliberately not started.

**Stopped after Phase 2 validation, as instructed. Phase 3 not started.**
