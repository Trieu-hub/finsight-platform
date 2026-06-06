# FinSight Full-Stack Validation Report

**Date:** 2026-06-06
**Scope:** Pre–Phase-2 infrastructure validation of the complete Docker Compose stack
and the API Gateway (Phase 1, routing-only edge). **Validation only** — no code,
routing, or auth changes were made.
**Environment:** Windows 11, Docker Desktop 28.5.1 (engine), Compose v2.40.3.

---

## 1. Executive summary

| Area | Result |
|------|--------|
| Compose stack builds (5 images) | ✅ Pass |
| Datastores healthy (mysql, postgres, redis) | ✅ Pass |
| auth / transaction / budget services healthy | ✅ Pass |
| **user-service healthy** | ❌ **Fail — crash-loop (Flyway vs PostgreSQL 16)** |
| Gateway route resolution | ✅ Pass |
| Request forwarding through :8080 | ✅ Pass |
| Status-code propagation | ✅ Pass |
| Timeout behavior (504) | ✅ Pass |
| Service-unavailable behavior (503) | ✅ Pass |

**Verdict:** The gateway and three of four backends are fully functional end-to-end.
**One blocking defect** prevents the stack from being 100% healthy: `user-service`
cannot start. Two lower-severity configuration gaps were also found (stale container
name collision on startup; `/api/v1/categories` not routed by the gateway).

The gateway itself behaved correctly in **every** tested dimension, including the two
failure paths — it is sound. None of the findings are in the gateway or in
`budget-service`.

---

## 2. Stack startup

`docker compose up -d --build` built all five service images successfully
(auth, user, transaction, budget, api-gateway). Final container state:

| Container | State | Notes |
|-----------|-------|-------|
| finsight-mysql | running (healthy) | shared by auth/transaction/budget |
| finsight-postgres | running (healthy) | user_db |
| finsight-redis | running (healthy) | used by auth-service |
| finsight-auth-service | running, `/actuator/health` = UP | port 8081 |
| finsight-transaction-service | running, `/actuator/health` = UP | port 8083 |
| finsight-budget-service | running, `/actuator/health` = UP | port 8084 |
| finsight-api-gateway | running, `/actuator/health` = UP | port 8080 |
| **finsight-user-service** | **restarting (13+ restarts, exit 1)** | port 8082 — never UP |

> Note: the application containers (the 4 services + gateway) have **no
> compose-level healthcheck**, only the datastores do. "running" therefore does not
> imply "ready"; readiness above was confirmed independently via each service's
> `/actuator/health` endpoint returning `{"status":"UP"}`.

---

## 3. Gateway route resolution (against real containers)

First-matching-prefix routing verified live:

| Request to :8080 | Result | Correct? |
|------------------|--------|----------|
| `GET /api/v1/auth/...` | routed → auth-service | ✅ |
| `GET /api/v1/transactions` | routed → transaction-service | ✅ |
| `GET /api/v1/budgets` | routed → budget-service | ✅ |
| `GET /api/v1/users/me` | routed → user-service | ✅ (routing OK; backend down) |
| `GET /api/v1/nope/123` | `404 ROUTE_NOT_FOUND` | ✅ |
| `GET /` | `404 ROUTE_NOT_FOUND` | ✅ |
| `GET /api/v1/categories` | `404 ROUTE_NOT_FOUND` | ⚠️ see Finding #3 |

Unknown-route body is the documented envelope:
`{"success":false,"error":{"code":"ROUTE_NOT_FOUND","message":"No route matches /api/v1/nope/123"}}`

---

## 4. Real requests through :8080

A real user was registered and logged in **through the gateway** against auth-service,
yielding a valid JWT (`userId:1`, `ROLE_USER`). That token was then used to drive the
other services through the gateway.

### Request forwarding + status-code propagation

| Service | GW + token | GW − token | Direct + token | Verdict |
|---------|-----------|-----------|----------------|---------|
| auth `/api/v1/auth/me` | 200 | 401 | 200 | ✅ propagated |
| transaction `/api/v1/transactions` | 200 | 401 | 200 | ✅ propagated |
| budget `/api/v1/budgets` | 200 | 401 | 200 | ✅ propagated |
| user `/api/v1/users/me` | 503 | 503 | 000 (refused) | ✅ gateway 503 (backend down) |

- **Forwarding:** request method, headers (incl. `Authorization`), and body are passed
  through unchanged; downstream JSON returned intact (success envelopes + pagination
  `meta` preserved, e.g. `{"success":true,"data":[],"meta":{"page":1,"limit":10,"total":0}}`).
- **Status-code propagation:** gateway codes match direct-to-service codes exactly. A
  downstream `401` (missing token) is relayed verbatim — the gateway does **not**
  authenticate (correct for Phase 1).
- **Auth round-trip** (register → login → authorized call) works fully through :8080.

---

## 5. Failure-path behavior

Both gateway error paths were exercised via external fault injection (no gateway/route
changes):

| Path | How induced | Observed | Expected | Verdict |
|------|-------------|----------|----------|---------|
| **Service unavailable** | user-service down → TCP connection refused | `503 SERVICE_UNAVAILABLE`, returned immediately | 503 | ✅ |
| **Timeout** | `docker pause finsight-budget-service` → connect succeeds, no response | `504 SERVICE_TIMEOUT` after **10.01s** | 504 at read-ms=10000 | ✅ |

Both returned the correct envelope, e.g.
`{"success":false,"error":{"code":"SERVICE_TIMEOUT","message":"Downstream service did not respond in time"}}`.
budget-service returned `200` again immediately after `docker unpause` (clean
recovery). The gateway correctly distinguishes "routed but unreachable" (503) from
"routed but slow" (504), and from "no route" (404).

---

## 6. Issues found

### Finding #1 — 🔴 BLOCKING: user-service crash-loops on PostgreSQL 16
- **Symptom:** container restarts continuously (13+ restarts, exit 1); never reaches
  `/actuator/health`. All requests to `/api/v1/users/**` get `503` at the gateway.
- **Root cause:** Flyway fails at startup —
  `org.flywaydb.core.api.FlywayException: Unsupported Database: PostgreSQL 16.14`,
  which aborts the `flywayInitializer` bean and the Spring context.
- **Why:** Flyway 10+ moved PostgreSQL support into a separate module. user-service
  ships `flyway-core` 11.14.1 but is **missing the `flyway-database-postgresql`
  dependency**, so Flyway does not recognize the Postgres 16 connection.
- **Category:** dependency / configuration (build-time), **not** networking.
- **Suggested fix (for a later, non-validation change):** add
  `org.flywaydb:flyway-database-postgresql` to `user-service/pom.xml`. (Out of scope
  for this validation; noted only.)

### Finding #2 — 🟠 Stale container name collision blocks first `up`
- **Symptom:** initial `docker compose up -d --build` failed at runtime (not build)
  with: `Conflict. The container name "/finsight-redis" is already in use`.
- **Root cause:** a leftover `finsight-redis` container (`Exited`, from a prior run)
  occupied the fixed `container_name: finsight-redis`. Fixed `container_name` values
  prevent Compose from reconciling pre-existing containers automatically.
- **Resolution applied:** `docker rm -f finsight-redis`, then `up` succeeded.
- **Category:** container lifecycle / configuration. Recurs whenever a previous stack
  is left with stopped containers; `docker compose down` between runs avoids it.

### Finding #3 — 🟡 `/api/v1/categories` is not routed by the gateway
- **Symptom:** transaction-service exposes `GET/POST /api/v1/categories` (and
  `/api/v1/categories/{id}`), but the gateway route table only has
  `/api/v1/transactions`. Requests to `/api/v1/categories` via :8080 → `404
  ROUTE_NOT_FOUND`, even though the endpoint exists and works when called directly on
  :8083.
- **Category:** routing configuration gap (the gateway is correct per its config; the
  config is incomplete relative to transaction-service's API surface).
- **Note:** Recorded only. Per instructions, routing behavior was **not** modified.

### Non-issues / observations
- DNS / service discovery on the compose network: ✅ healthy services resolve and are
  reached by hostname (`auth-service:8081`, etc.); confirmed by successful end-to-end
  forwarding.
- Port publishing: ✅ 8080–8084 all bound on the host as configured; datastores
  intentionally unpublished (as documented in `docker-compose.yml`).
- Application containers lack a compose `healthcheck`, so `depends_on` for the gateway
  only waits for "started", not "ready". Not a failure here, but it means the gateway
  can start before backends are accepting requests. (Observation, not a defect.)

---

## 7. Reproduction commands

```powershell
# Start
docker rm -f finsight-redis            # only if a stale one exists (Finding #2)
docker compose -f D:\finsight\docker-compose.yml up -d --build

# Health
curl.exe http://localhost:8080/actuator/health        # gateway
curl.exe http://localhost:8081/actuator/health        # auth (8082 user is DOWN)

# Auth round-trip through the gateway, then call a service
#   POST /api/v1/auth/register, POST /api/v1/auth/login -> accessToken
#   curl.exe -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/budgets

# Timeout path
docker pause finsight-budget-service
curl.exe -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/budgets   # 504 after 10s
docker unpause finsight-budget-service
```

---

## 8. Conclusion

The API Gateway (Phase 1) and the auth, transaction, and budget services are validated
and behave correctly end-to-end through :8080 — forwarding, status-code propagation,
timeout (504), and service-unavailable (503) all pass. **Phase 2 / authentication work
should not begin until Finding #1 (user-service / Flyway-PostgreSQL) is resolved**, as
one quarter of the platform is currently non-functional. Findings #2 and #3 are minor
and can be addressed alongside other work.

*No source, routing, or authentication changes were made during this validation.*
