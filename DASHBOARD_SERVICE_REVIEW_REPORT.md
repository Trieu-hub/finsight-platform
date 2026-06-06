# Dashboard Service — Implementation & Validation Review

**Date:** 2026-06-06
**Scope delivered:** a new **read-only aggregation layer / BFF** (`dashboard-service`)
exposing two aggregated views, routed through the gateway. Approved design decisions:
stateless (no DB), direct service-to-service calls with JWT relay, both endpoints,
fail-fast 502. Architectural pattern frozen in **ADR-0003**.
**Outcome:** ✅ Implemented, unit-tested (12/12), validated end-to-end through `:8080`.
**Within the stated guardrails:** no CQRS / event sourcing / Kafka / RabbitMQ / k8s /
distributed transactions; no rewrites or API renames; constructor injection + immutable
record DTOs throughout.

---

## 1. What was built

A stateless Spring Boot 4 service on **port 8085** that owns no database. It composes
read views by calling the existing REST APIs of Transaction, Budget and User, relaying
the caller's JWT so each upstream authorizes the same user. It also validates the JWT
locally (service-level validation is never removed) — the gateway validating at the edge
does not replace it.

**Endpoints** (`/api/v1/dashboard`, authenticated):
- `GET /budget-progress?fromDate&toDate` — joins each budget's limit with the user's
  **EXPENSE** spend per category over the window (defaults to the current month) →
  `{ limitAmount, spentAmount, remainingAmount, percentUsed }` per budget. This is the
  spent-vs-limit view budget-service deliberately deferred to a BFF.
- `GET /overview` — `{ profile, currentMonth (income/expense/balance), budgets[] }`.

**Files created** (`services/dashboard-service/`): `pom.xml` (no JPA/Flyway/MySQL/
Testcontainers), `Dockerfile`, `application.yml`, `DashboardApplication`; `security/`
(JwtAuthenticationFilter, JwtService, JwtUserPrincipal, JwtProperties, SecurityConfig —
mirrors the other services); `config/` (DashboardProperties, RestClientConfig with
timeouts); `client/` (Budget/Transaction/User clients + upstream DTOs + envelope record);
`service/DashboardService`; `controller/DashboardController`; `dto/` (ApiResponse,
BudgetProgressItem/Response, OverviewResponse); `exception/` (ApiError, ErrorResponse,
UpstreamException, GlobalExceptionHandler); tests.

**Files modified:** `docker-compose.yml` (+`dashboard-service`; gateway `depends_on` +
`DASHBOARD_SERVICE_URI`); `api-gateway/.../application.yml` (+route `/api/v1/dashboard`);
`README.md` (service table + cross-service-call rule exception); new
`api-gateway/docs/ADR-0003-dashboard-bff-token-relay.md`.

### Conventions followed
- Response envelope `{success,data}` / error `{success:false,error:{code,message}}`,
  matching the platform. Constructor injection only; DTOs are immutable `record`s.
- Same jjwt 0.12.6 stack; HS512 shared-secret validation identical to the services.
- **No DB → Testcontainers not applicable**; upstream HTTP is stubbed with
  `MockRestServiceServer` (ships with `spring-boot-starter-test`).

## 2. Unit tests — `mvnw test` (dashboard-service)

```
Tests run: 12, Failures: 0, Errors: 0 — BUILD SUCCESS
  DashboardServiceTest        : 6   (join math, INCOME ignored, unspent→0, default window, %% rounding, overview compose, null profile tolerated)
  BudgetClientTest            : 2   (envelope unwrap + JWT relay; 5xx → UpstreamException)
  UserClientTest              : 3   (raw profile + JWT relay; 404 → null; 5xx → UpstreamException)
  DashboardApplicationTests   : 1   (context loads: security + clients + config wiring)
```

## 3. End-to-end validation through `:8080` (full stack, 6 services up)

Seeded a user (profile), a budget (category 4 "Food & Dining", limit 500, June 2026),
and two EXPENSE transactions (120 + 30) — all via the gateway with the user's token.

**`GET /api/v1/dashboard/budget-progress?fromDate=2026-06-01&toDate=2026-06-30`:**
```
items[0] = { categoryId:4, limitAmount:500, spentAmount:150, remainingAmount:350, percentUsed:30.00 }
```
✅ Correct join (120+30 EXPENSE vs 500 limit = 30.00%).

**`GET /api/v1/dashboard/overview`:**
```
profile      = { userId:13, fullName:"Dash User", occupation:"Engineer" }
currentMonth = { income:0, expense:150, balance:-150 }
budgets      = [ { name:"Food", categoryId:4, limitAmount:500, ... } ]
```
✅ Profile + current-month summary + budgets composed from three services.

**Auth & failure contract:**
| Scenario | Result |
|----------|--------|
| `dashboard/overview` with no token | `401 UNAUTHENTICATED` (gateway edge) |
| valid token → both endpoints | `200` (gateway-validated, dashboard-validated, token relayed to 3 upstreams) |
| upstream down (`docker pause` budget-service) | `502 DASHBOARD_UPSTREAM_ERROR` (fail-fast), recovered after unpause |

This proves the full chain: gateway routes `/api/v1/dashboard` and enforces edge auth;
the dashboard validates locally and **relays the user's JWT** so each upstream returns
that user's data; aggregation is correct; failures fail fast.

## 4. Architecture & guardrail adherence

- **First sanctioned cross-service runtime call.** The core business services remain
  non-calling; `dashboard-service` is the single documented BFF exception (ADR-0003,
  README updated). It owns no data and is removable without touching any other service.
- **No existing code changed** beyond additive compose/gateway-route/README/ADR wiring.
  No API renamed; no downstream service modified.
- **Explicitly avoided:** CQRS, event sourcing, Kafka/RabbitMQ, k8s, distributed
  transactions, a Dashboard-owned database, gateway-hairpin calls.

## 5. Risks / notes (non-blocking)

- **Availability coupling** — Dashboard depends on its upstreams; bounded by per-call
  timeouts (connect 2s / read 5s) + fail-fast 502. Partial degradation deferred.
- **Latency** — `overview` fans out to 3 services sequentially; parallelization is a
  future optimization, intentionally not done (no premature optimization).
- **Envelope inconsistency handled** — user-service returns the profile raw; transaction/
  budget wrap in `{success,data}`. The client layer models both (verified by tests).
- **Opaque categoryId** — budget×spend join is best-effort on matching ids: budgeted
  categories with no spend show `spent=0`; spend in un-budgeted categories is excluded
  from budget-progress (by design).
- **Maven wrapper** added to the module (it had none) to enable `mvnw test`.

## 6. Conclusion

The Dashboard Service is implemented and validated as a clean, stateless BFF: it
aggregates read-only views from Transaction/Budget/User via their APIs, relays the
caller's JWT, preserves service-level validation, fails fast on upstream errors, and is
routed and edge-authenticated through the gateway. It delivers the previously-deferred
budget spent-vs-limit view. Implementation stayed within the project's architectural
guardrails and conventions.
