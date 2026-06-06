# Dashboard Service — Architecture & Design Review

- Status: **Design (review only — no code)**
- Date: 2026-06-06
- Relates to: ADR-0001 (gateway), ADR-0002 (auth contract), ADR-0003 (BFF token relay)

## 0. Context & relationship to the existing service

A v1 `dashboard-service` already exists (stateless BFF on port **8085**, routed at
`/api/v1/dashboard`, JWT-validated, with `/overview` and `/budget-progress`). **This
document is the consolidated design for the full 5-feature dashboard** and treats the
existing endpoints as the starting point. Net change vs. v1:

| Feature (required) | Endpoint | Status vs v1 |
|---|---|---|
| 1. Dashboard Summary (income/expense/balance/utilization) | `GET /summary` | **new** |
| 2. Recent Transactions | `GET /recent-transactions` | **new** |
| 3. Budget Overview | `GET /budget-overview` | exists as `/budget-progress` → rename/alias |
| 4. Top Spending Categories | `GET /top-categories` | **new** |
| 5. Monthly Trend | `GET /trend` | **new** |
| (BFF convenience) full dashboard | `GET /` (composite) | supersedes `/overview` |

All constraints are satisfied: **no DB access to other services, API-only, stateless,
follows FinSight conventions.** Implementation is a follow-up (not in this review).

---

## 1. Package structure

`com.pm.dashboardservice` (mirrors the other services; only additive growth over v1):

```
com.pm.dashboardservice
├── DashboardApplication
├── config/
│   ├── DashboardProperties      # upstream base URIs + timeouts (@ConfigurationProperties)
│   └── RestClientConfig         # shared RestClient.Builder with connect/read timeouts
├── security/
│   ├── SecurityConfig           # stateless; /api/v1/dashboard/** authenticated
│   ├── JwtUserPrincipal
│   └── jwt/{JwtAuthenticationFilter, JwtService, JwtProperties}
├── client/                      # outbound (anti-corruption layer)
│   ├── TransactionClient        # summaries, trend, recent list
│   ├── BudgetClient             # budget definitions
│   ├── UserClient               # profile
│   ├── UpstreamApiResponse<T>   # {success,data} envelope wrapper
│   └── dto/                     # INBOUND DTOs (mirror upstream JSON)
│       ├── TransactionDto, CategorySummaryDto, MonthlySummaryDto, TrendPointDto
│       ├── BudgetDto
│       └── UserProfileDto
├── service/
│   └── DashboardService         # orchestration + aggregation (facade)
├── controller/
│   └── DashboardController       # thin; relays bearer token, wraps in ApiResponse
├── dto/                         # OUTBOUND DTOs (view models returned to clients)
│   ├── ApiResponse<T>
│   ├── DashboardSummaryResponse, BudgetUtilization
│   ├── RecentTransactionItem
│   ├── BudgetOverviewItem
│   ├── TopCategoryItem
│   ├── TrendPoint
│   └── DashboardResponse        # composite (bundles all blocks)
└── exception/
    └── {ApiError, ErrorResponse, UpstreamException, GlobalExceptionHandler}
```

Note the deliberate **two DTO packages**: `client.dto` (inbound, upstream-shaped) vs
`dto` (outbound, dashboard-shaped). This decouples our API from upstream changes
(anti-corruption). One `DashboardService` facade keeps it simple — no per-feature
service classes unless a block grows non-trivial (avoid premature abstraction).

## 2. Entity strategy

**There are no persistence entities — by design.** The Dashboard owns no data, no
database, no JPA, no Flyway, no repositories. This is the correct "entity strategy" for
a read-only BFF:

- No `@Entity`, no `JpaRepository`, no `spring-boot-starter-data-jpa`, no datasource.
- The only "models" are **transient view objects** (DTOs/records) built per request and
  discarded after the response. They are immutable and carry no identity or lifecycle.
- Consequence: the service is trivially horizontally scalable and has nothing to migrate
  or back up. It is removable without affecting any other service.

(If a future caching layer is added, cache entries are derived projections keyed by
`userId`+params — still not domain entities; see §8.)

## 3. DTO design

### 3.1 Inbound (upstream-shaped, `client.dto`, `@JsonIgnoreProperties(ignoreUnknown=true)`)
Mirror only the fields we consume; unknown fields (e.g. `meta`, audit timestamps) ignored.
- `BudgetDto(id, name, categoryId, periodType, startDate, endDate, limitAmount, currency)`
- `CategorySummaryDto(categoryId, categoryName, type, total, count)` — `type` ∈ INCOME/EXPENSE
- `MonthlySummaryDto(income, expense, balance)`
- `TrendPointDto(date, income, expense, balance)` — **daily** granularity
- `TransactionDto(id, type, amount, currency, categoryId, description, transactionDate)`
- `UserProfileDto(userId, fullName, avatarUrl, occupation, …)`
- `UpstreamApiResponse<T>(success, data)` — the `{success,data,meta}` envelope (transaction/
  budget). **User-service returns its profile raw** (no envelope) — handled separately.

### 3.2 Outbound (dashboard-shaped, `dto`, immutable records, `{success,data}` envelope)
- `DashboardSummaryResponse(LocalDate fromDate, toDate, BigDecimal totalIncome,
   totalExpense, remainingBalance, BudgetUtilization budgetUtilization)`
- `BudgetUtilization(BigDecimal totalLimit, totalSpent, BigDecimal utilizationPercent)`
- `RecentTransactionItem(UUID id, String type, BigDecimal amount, String currency,
   Long categoryId, String description, LocalDate transactionDate)`
- `BudgetOverviewItem(UUID budgetId, name, Long categoryId, String currency, periodType,
   LocalDate startDate, endDate, BigDecimal limitAmount, spentAmount, remainingAmount,
   percentUsed)`  *(this is today's `BudgetProgressItem`)*
- `TopCategoryItem(Long categoryId, String categoryName, BigDecimal totalSpent,
   long transactionCount, BigDecimal percentOfTotal)`
- `TrendPoint(LocalDate date, BigDecimal income, expense, balance)` (passthrough; or
   `period:String` if monthly-bucketed — see §6/§8)
- `DashboardResponse(UserProfileDto profile, DashboardSummaryResponse summary,
   List<RecentTransactionItem> recentTransactions, List<BudgetOverviewItem> budgetOverview,
   List<TopCategoryItem> topCategories, List<TrendPoint> trend)` — the composite/BFF view.

Money is `BigDecimal`; percentages `BigDecimal` scale 2, HALF_UP.

## 4. External client strategy

- **One `RestClient` per upstream** (`TransactionClient`, `BudgetClient`, `UserClient`),
  base URI from `DashboardProperties` (env-injected compose DNS), built from a shared
  `RestClient.Builder` carrying **connect (2s) / read (5s) timeouts**.
- **JWT relay (passthrough):** every outbound call forwards the caller's
  `Authorization: Bearer …` unchanged (ADR-0003). Upstreams validate it and scope data to
  that user. No service account, no userId in any URL/body.
- **Envelope handling:** transaction/budget responses unwrapped via
  `ParameterizedTypeReference<UpstreamApiResponse<…>>`; user profile read raw; a 404 from
  user-service = "no profile yet" → null (not an error).
- **Anti-corruption:** clients return inbound `client.dto` types only; the service maps to
  outbound `dto`. Upstream shape changes are absorbed in the client layer.
- **Failure model = fail-fast (current decision):** any upstream timeout/4xx/5xx →
  `UpstreamException` → **HTTP 502 `DASHBOARD_UPSTREAM_ERROR`** in the standard error
  envelope. No retries in v1 (a bounded retry / circuit breaker is a §8 option).
- **Fan-out efficiency:** the composite endpoint needs ≤5 upstream calls; shared data
  (budgets + category summary) is fetched once and reused across the summary, budget-
  overview and top-categories blocks. Calls are independent → parallelizable (§8).

## 5. Security design

Identical posture to the rest of the platform; nothing weakened:
- **Edge:** gateway validates the JWT (HS512 / `iss=finsight-auth` / `aud=finsight-api`,
  ADR-0002) and routes `/api/v1/dashboard/**` (prefix already configured — sub-paths need
  no new gateway route).
- **Service:** Dashboard **also** validates the JWT locally (same shared HMAC secret) via
  its `JwtAuthenticationFilter` — service-level validation is never removed. Stateless
  (`SessionCreationPolicy.STATELESS`); only `/actuator/health|info` public; everything
  else authenticated.
- **`userId` is sacred:** taken only from the validated token; never from query/body.
- **Token relay** to upstreams (§4) — the relayed token is never logged.
- **No new secrets, no Redis, no new auth mechanism.**

## 6. API endpoints

All under `/api/v1/dashboard`, all authenticated, all read-only (`GET`), all return the
`{success,data}` envelope. Date params optional (default = current month). `limit` bounded.

| Endpoint | Params | Feature | Upstream calls |
|----------|--------|---------|----------------|
| `GET /summary` | `fromDate,toDate` | 1 — KPIs | budgets + `summary/categories` |
| `GET /recent-transactions` | `limit`(≤50, def 10) | 2 | `GET /transactions?page=1&limit=N` |
| `GET /budget-overview` | `fromDate,toDate` | 3 | budgets + `summary/categories` |
| `GET /top-categories` | `limit`(≤20, def 5), `fromDate,toDate` | 4 | `summary/categories` |
| `GET /trend` | `fromDate,toDate`,`granularity`(day\|month, def month) | 5 | `summary/trend` |
| `GET /` (composite) | `fromDate,toDate`,`limit` | all | budgets + categories + trend + tx list + profile |

Derivations:
- **Summary:** `totalIncome = Σ category.total where type=INCOME`; `totalExpense = Σ … EXPENSE`;
  `remainingBalance = income − expense`; `budgetUtilization = Σspent(budgeted cats) / Σlimit`.
- **Top categories:** EXPENSE rows sorted by `total` desc, top N, with `percentOfTotal`.
- **Budget overview:** per-budget join of limit × EXPENSE spend in its category (today's
  `/budget-progress`).
- **Trend:** upstream is **daily**; `granularity=month` ⇒ dashboard buckets daily points
  into months (sum income/expense). `granularity=day` ⇒ passthrough. (See §8 for moving
  bucketing upstream.)

> Migration note: keep `/budget-progress` as a deprecated alias of `/budget-overview`, and
> have the composite `GET /` supersede `/overview`. No breaking change required.

## 7. Sequence diagrams

### 7.1 Composite dashboard (happy path, token relay, parallel fan-out)
```
Client        Gateway(8080)        Dashboard(8085)        Txn / Budget / User
  |  GET /api/v1/dashboard            |                          |
  |  Authorization: Bearer J          |                          |
  |----------------->|                 |                          |
  |        validate J (HS512/iss/aud)  |                          |
  |                  |  GET /api/v1/dashboard (J forwarded)       |
  |                  |---------------->|                          |
  |                  |        validate J locally                  |
  |                  |   fan-out (parallel), each relays Bearer J |
  |                  |                 |-- GET /budgets --------->| (Budget)
  |                  |                 |-- GET /summary/categories>| (Txn)
  |                  |                 |-- GET /summary/trend ---->| (Txn)
  |                  |                 |-- GET /transactions?N --->| (Txn)
  |                  |                 |-- GET /users/me --------->| (User)
  |                  |                 |<==== 200 (user-scoped) ===|
  |                  |        aggregate → DashboardResponse        |
  |                  |<----------------|                          |
  |   200 {success,data:{summary,recent,budgetOverview,top,trend,profile}}
  |<-----------------|                 |                          |
```

### 7.2 Single feature — Top Spending Categories
```
Client -> Gateway: GET /api/v1/dashboard/top-categories?limit=5  (Bearer J)
Gateway: validate J -> forward
Dashboard: validate J -> GET /api/v1/transactions/summary/categories?fromDate&toDate (Bearer J)
Txn -> Dashboard: 200 [category rows]
Dashboard: filter EXPENSE, sort desc, take 5, compute percentOfTotal
Dashboard -> Gateway -> Client: 200 {success, data:[TopCategoryItem...]}
```

### 7.3 Upstream failure (fail-fast)
```
Dashboard -> Budget: GET /budgets (Bearer J)
Budget: (down / timeout)  --X
Dashboard: RestClientException -> UpstreamException("budget-service")
Dashboard -> Client: 502 {success:false, error:{code:"DASHBOARD_UPSTREAM_ERROR", ...}}
```

## 8. Future scalability considerations

Ordered by likely value; none required for v1, all compatible with the constraints.

1. **Parallel fan-out** — issue the composite's independent upstream calls concurrently
   (`CompletableFuture`/structured concurrency) to cut latency from Σ to max. Pure
   in-process; no new infra.
2. **Short-TTL per-user cache** — cache aggregates keyed by `userId`+params for a few
   seconds to absorb refresh bursts. *Per the platform constraint, introduce Redis only
   with a clear perf reason*; start with an in-process Caffeine cache (stateless-friendly,
   per-instance) before considering Redis.
3. **Resilience** — bounded retry on idempotent GETs + a circuit breaker (Resilience4j) per
   upstream; optional **partial degradation** mode (return available blocks with per-block
   error markers) as an alternative to fail-fast for the composite.
4. **Upstream enhancements this design surfaces** (small, additive):
   - a `sort` param on `GET /transactions` so *Recent Transactions* is order-guaranteed
     (today it relies on the default order — see §Dependencies);
   - a `granularity=day|month` on `summary/trend` to push monthly bucketing upstream;
   - a date-range income/expense summary (today derived from `summary/categories`).
5. **Observability** — once the gateway adds correlation IDs (`X-Request-Id`, ADR-0002
   §7 / Phase 3), propagate them on all fan-out calls for end-to-end tracing.
6. **Transport efficiency** — response gzip; `ETag`/conditional GET on the composite;
   field selection (`?include=summary,trend`) to let clients fetch only needed blocks.
7. **Horizontal scale** — stateless ⇒ run N replicas behind the gateway with no
   coordination. Cache (if added per-instance) stays local; Redis only if a shared cache
   is justified.
8. **Per-client BFFs / GraphQL** — if web vs mobile needs diverge, split BFFs or expose a
   read-only GraphQL facade over the same clients. Deferred; not needed now.

Explicitly **out of scope / rejected** (platform rules): CQRS, event sourcing,
Kafka/RabbitMQ, k8s, distributed transactions, a Dashboard-owned database, materialized
read models, and gateway-hairpin internal calls.

---

## Upstream dependencies & assumptions (flagged, not yet resolved)

- **Recent Transactions** assumes `GET /transactions` returns newest-first by default;
  there is no `sort` param today. If the default is not date-desc, add one upstream.
- **Monthly Trend**: `summary/trend` returns **daily** points; monthly granularity is
  done by dashboard-side bucketing unless an upstream `granularity` param is added.
- **Range income/expense** for Summary is derived by summing `summary/categories` rows by
  `type` over the window (no dedicated range-summary endpoint exists).
- Gateway prefix route `/api/v1/dashboard` already covers all new sub-paths (prefix match)
  — no gateway change needed for the new endpoints.
