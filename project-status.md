# FinSight — Project Status

_Last updated: 2026-06-11_
_Repo: `D:\finsight` · Branch: `feature/ci-github-actions`_

FinSight's **original vision** is a *Financial Intelligence & Risk Monitoring Platform* —
explicitly **not** just an expense tracker. This document measures progress against that
chartered scope, not against the current repository alone.

**Stack (today):** Java 21 · Spring Boot 4.0.6 · Spring Security · Spring Data JPA · Flyway ·
MySQL 8.4 · Redis · JWT (HS512) · Docker Compose · GitHub Actions · Testcontainers.

---

## 0. Progress at a glance — three axes (not one number)

A single "% complete" mixes two very different goals and creates a false sense of
near-completion. Progress is tracked on three independent axes:

| Axis | What it measures | Progress |
|---|---|---|
| **MVP backend** | Core finance CRUD + auth + dashboard working end-to-end | **~85–90%** |
| **Production-ready MVP** | The MVP, operable & secure for real deployment | **~55–65%** |
| **Full FinSight vision** | The chartered Intelligence & Risk platform | **~40–45%** |

**Headline:** The MVP (finance tracker + budgets + dashboard) is nearly done and is well
built. But the product's defining half — *Intelligence & Risk* — is essentially unbuilt:
**3 of 5 core domains are at 0%**, and of the 3 communication pillars only REST is mature —
**gRPC does not exist** and **Kafka has just been bootstrapped** (Phase 2.1: one producer,
no consumers — see §0.6). Today the system is, in practice, a polished expense tracker —
the thing the charter said it must not merely be.

---

## 0.5. Phase 1 — Observability & Developer Experience (IN PROGRESS)

_Branch `feature/ci-github-actions`. Work proceeds in small, individually-verified
commits — `./mvnw verify` on every touched service before each commit._

**Goal:** OpenAPI/Swagger · correlation-ID propagation · structured JSON logging.

### Done
| Step | What | Commit | Services |
|---|---|---|---|
| 1 | Correlation-ID propagation (`X-Correlation-ID`: reuse-or-generate, MDC, response echo; gateway forwards a single canonical id; dashboard relays to upstreams via a `RestClient` interceptor) | `f67fe19` | gateway, dashboard, transaction |
| 2 | Native Boot 4 **ECS JSON logging**, toggled by `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (set in docker-compose); MDC `correlationId` + `service.name` auto-included; var absent ⇒ unchanged plain text | `c97ad93` | gateway, dashboard, transaction |
| 5 | **OpenAPI/Swagger pilot**: springdoc 3.0.3, `OpenApiConfig` (metadata + bearer-JWT scheme), Swagger paths permitted in SecurityConfig | `06b3548` | transaction only |

### Pending (pick up here next session)
- **Step 3 — roll correlation-ID + ECS logging out to `auth`, `user`, `budget`** (the 3 not yet touched). Same pattern as the done services: one `CorrelationIdFilter` + `CorrelationIdFilterConfig` per module, plus `LOGGING_STRUCTURED_FORMAT_CONSOLE: ecs` in each docker-compose block.
- **Step 6 — roll Swagger out to `auth`, `user`, `budget`, `dashboard`** (same springdoc 3.0.3 + `OpenApiConfig` + SecurityConfig permit-list as the transaction pilot).
- Then: Micrometer/Prometheus metrics → Grafana → alerting.

### Key facts / decisions (don't re-derive these)
- **springdoc `3.0.3`** is the verified Spring Boot 4 / Spring 7 line — `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3`. It pins no Boot version, so it composes with the app's Boot 4.0.6 BOM. Limitation: issue #3163 (HTTP 400 with Boot 4 API-versioning) — N/A, we don't use API versioning. Swagger UI is currently **unauthenticated on the service port** (fine for pilot; for prod disable via `springdoc.api-docs.enabled=false` / `springdoc.swagger-ui.enabled=false`).
- **Boot 4 / Spring 7 API change that bit us:** `HttpHeaders.containsKey(...)` was removed (HttpHeaders no longer implements `Map`) → use **`containsHeader(...)`**.
- `FilterRegistrationBean` is still `org.springframework.boot.web.servlet.FilterRegistrationBean` in Boot 4.
- Correlation filters register at **`Ordered.HIGHEST_PRECEDENCE`** (ahead of Spring Security) so MDC covers error/auth-failure logs too; `try/finally` MDC cleanup prevents thread-pool leakage. Header name is a per-module constant `CorrelationIdFilter.CORRELATION_ID_HEADER` (`X-Correlation-ID`), MDC key `correlationId`. No shared lib across services → the constant is duplicated per module by design.
- **ECS logging needs NO dependency and NO `logback-spring.xml`** (`logging.structured.format.console=ecs`); Boot's own JSON writer is used (works even on the gateway, which has no Jackson). MDC inclusion is default-on; `service.name` comes from `spring.application.name`.

### Build / verify environment (important)
- **No `mvn` on PATH** — use the wrapper **`./mvnw`**; it self-bootstraps Maven 3.9.16 from `~/.m2/wrapper` (`distributionType=only-script`, no wrapper jar committed; CI uses preinstalled `mvn`).
- **Local JDK is 23; CI is JDK 21** (project targets 21). Local `./mvnw verify` is a valid pre-check; **CI on JDK 21 is authoritative.**
- transaction-service tests use **Testcontainers-MySQL → Docker must be running.** The runtime Swagger smoke test used an ephemeral `mysql:8.4` on host port 13306.
- HS512 needs a **≥64-byte `JWT_SECRET`** or the app won't start (use a 64-char dummy for local boots).

---

## 0.6. Phase 2.1 — Kafka Foundation (DONE locally; not yet committed / CI-run)

_Branch `feature/ci-github-actions`. **transaction-service only** — no consumers, no Budget
Service, no gateway/Swagger changes._

**Goal:** stand up the async event backbone and publish the first domain event end-to-end.

### Done
| What | Detail |
|---|---|
| KRaft broker | Single-node `apache/kafka:3.9.1` (no Zookeeper) in docker-compose, RF=1, healthcheck; transaction-service `depends_on` it + `KAFKA_BOOTSTRAP_SERVERS`. |
| Producer | `spring-boot-starter-kafka`; JSON value, keyed by `userId`, `acks=all`, topic **`finsight.transactions.created`** (`NewTopic` bean, 1 partition). |
| Event contract | `TransactionCreatedEvent` (record): envelope (`eventId`/`eventType`/`occurredAt`) + transaction snapshot; decoupled from the JPA entity; ISO-8601 string temporals. |
| Publish timing | `@TransactionalEventListener(AFTER_COMMIT)`, emitted from `TransactionServiceImpl.create()` — shipped only after the DB commit; delivery failure logged, not rethrown. |
| Verification | Testcontainers E2E test against a real KRaft broker: POST → consume → assert full payload + `userId` key. `./mvnw verify` green (48 tests, 0 failures). |

### Pending (pick up here next session)
- **Commit + green CI run (JDK 21)** — verified locally on JDK 23 only.
- **First consumer (Phase 2.2)** — the Risk/Anomaly or Analytics feed.
- **Harden delivery:** transactional outbox to close the commit-then-publish dual-write gap; consider async publish so a broker outage adds no latency to `create()`.
- Roll the producer pattern to other services once consumers exist.

### Key facts / decisions (don't re-derive these)
- **Boot 4 modularized autoconfiguration:** the raw `spring-kafka` library no longer registers `KafkaAutoConfiguration` (it did under Boot 3) ⇒ no `KafkaTemplate` bean and every `@SpringBootTest` context fails. Use **`spring-boot-starter-kafka`** (+ `spring-boot-starter-kafka-test`).
- **Default `JsonSerializer` writes `java.time` as Jackson timestamps** (LocalDate → `[2026,6,1]`, Instant → epoch) ⇒ `LocalDate` arrived as `""`. The contract therefore uses **ISO-8601 strings** for temporals — language-neutral and independent of the serializer's mapper config.
- **No-broker safety:** `finsight.kafka.enabled` (default `true`; **`false` in the test profile**) gates publishing, and producer `max.block.ms=10000` bounds the metadata wait — otherwise each `create()` blocks **60s** when no broker is present (one integration class took 1141s before this fix). The E2E test flips the flag back on via `@DynamicPropertySource`.
- Topic auto-creation (`spring.kafka.admin.auto-create`) is **off in the test profile** so MySQL-only tests never reach a broker at startup.

---

## 1. Chartered scope vs. reality

### Core domains (5)

| Core domain | Status | Notes |
|---|---|---|
| Personal Finance Management | ✅ ~85% | Transactions, categories, budgets |
| Dashboard & Analytics | ⚠️ partial | Dashboard ✅; *Analytics* domain only ~25–30% |
| Behavioral Insights | ❌ 0% | Not started |
| Anomaly Detection | ❌ 0% | Not started |
| Financial Risk Monitoring | ❌ 0% | Not started |

The three intelligence domains (Insights / Anomaly / Risk) are the product's identity and
its biggest gap — far larger than any single missing feature.

### Services (8 chartered)

| Service (charter) | Reality | Mapping / completeness |
|---|---|---|
| API Gateway | ✅ exists | Proxy + JWT validation; **no rate limiting** → ~80% |
| Auth Service | ✅ exists | JWT, refresh, lockout, Redis-backed → ~90% |
| User Service | ✅ exists | Profile data → ~85% |
| Transaction Service | ✅ exists | INCOME/EXPENSE + categories/summaries; **no TRANSFER** → ~75% |
| Budget Service | ✅ exists | Definitions + utilization → ~85% |
| **Analytics Service** | ⚠️ substituted | `dashboard-service` (BFF) covers presentation, not analysis → ~25–30% |
| **Risk Intelligence Service** | ❌ absent | 0% |
| **Notification Service** | ❌ absent | 0% (see §1.1 — scope conflict) |

`dashboard-service` is an **extra** BFF, not one of the 8 chartered services. It aggregates
user + transaction + budget over REST and renders summaries; it does **not** compute
behavioral insight, anomaly, or forecasting. The aggregate math it shows lives in
transaction-service's `SummaryService`.

### Communication pillars (3)

| Pillar | Charter | Reality |
|---|---|---|
| REST (external) | required | ✅ implemented |
| gRPC (internal sync) | required | ❌ **0%** — no proto, no deps; internal calls are REST (`RestClient`) |
| Kafka (async events) | required | ⚠️ **~15%** — Phase 2.1 foundation: transaction-service publishes `TransactionCreated` to a single-node KRaft broker (verified E2E); no consumers yet. See §0.6 |

### Infrastructure

| Item | Charter | Reality |
|---|---|---|
| MySQL (DB-per-service) | ✅ | ✅ logical DB-per-service on one instance |
| Redis | ✅ | ⚠️ only auth-service uses it (refresh tokens + lockout); gateway has the dep but **unused** |
| Kafka | ✅ | ⚠️ single-node KRaft broker in compose; transaction-service produces `TransactionCreated` (Phase 2.1) |
| Docker | ✅ | ✅ |
| CI/CD | ✅ | ✅ GitHub Actions (build + test) |
| OpenAPI/Swagger | ✅ | ❌ **0%** — cheapest gap to close, prioritize early |
| Monitoring (Prometheus/Grafana) | ✅ | ❌ absent |

### Explicitly out-of-scope for v1 (correctly absent)

Saga · CQRS · Event Sourcing · ELK · Keycloak · Istio · Helm · ArgoCD · ML · Blockchain ·
**separate Audit Service**. None of these count against progress.

### 1.1 Two scope clarifications

- **Audit:** the charter keeps audit as *in-service logging*, with a separate Audit Service
  only "if time allows." So a missing Audit *Service* is **not** a gap. **However**, the
  real v1 requirement — audit logging inside each service — is also **not implemented**;
  what exists is only JPA timestamp auditing (`@CreatedDate`/`@LastModifiedDate`), not
  action/security audit logging. So this item is *open*, just not as a separate service.
- **Notification — unresolved conflict:** it is listed in the chartered **architecture (1 of
  8 services)** and is **not** in the v1 out-of-scope list, so by the written charter it is
  **required**, not nice-to-have. This must be reconciled: either amend the charter to
  demote it, or count it as a missing core service. It cannot be both.

---

## 2. Architecture as built (what exists today)

| Service | Port | Owns DB | Responsibility |
|---|---|---|---|
| api-gateway | 8080 | – | Edge routing + JWT validation (HS512/iss/aud), error envelope |
| auth-service | 8081 | `auth_db` | Register, login, refresh, account lockout; Redis-backed tokens |
| user-service | 8082 | `user_db` | User profile data |
| transaction-service | 8083 | `transaction_db` | Transactions (INCOME/EXPENSE), categories, summaries (categories/trend/monthly) |
| budget-service | 8084 | `budget_db` | Budget definitions + utilization |
| dashboard-service | 8085 | none (BFF) | Aggregates user + transaction + budget; fail-fast; relays JWT |
| mysql / redis | – | – | Shared MySQL instance (DB-per-service logical isolation) + Redis |

**Design rules:** no runtime cross-service calls between business services (only the
dashboard BFF calls others); `userId` read only from the JWT; Flyway owns the schema
(`ddl-auto: validate`); shared HMAC secret; gateway is removable (services validate
tokens independently).

---

## 3. Capabilities accomplished (MVP)

- Working end-to-end auth flow: register → login → JWT (HS512) → authorized calls through
  the gateway, with per-account brute-force lockout and Redis-backed token/session data.
- Full CRUD + reporting in transaction and budget services (summaries, trends, utilization).
- A stateless BFF that aggregates three services with fail-fast behavior and JWT relay.
- Flyway-owned schemas, `validate`-only Hibernate, DB-per-service logical isolation.
- Defense-in-depth JWT validation (edge + every service), now consistent.
- Containerized local stack that comes up healthy with readiness-gated startup ordering.
- Automated CI and a meaningful Testcontainers-based integration test suite (51 test files).

---

## 4. Gaps, prioritized by impact on the vision

1. **Intelligence & Risk domains (Behavioral Insights, Anomaly Detection, Risk Monitoring)** —
   3/5 core domains at 0%. This is the single largest gap and the product's reason to exist.
2. **Kafka (async events)** — not an independent "advanced" feature: it is the data backbone
   the Risk/Anomaly domains depend on. **Foundation now laid** (Phase 2.1: transaction-service
   produces `TransactionCreated`; §0.6); the gap is now the *consumers* + remaining producers
   that turn it into a real event feed.
3. **Analytics Service** — a real analysis engine, distinct from the dashboard's presentation.
4. **gRPC (internal sync)** — architectural pillar at 0%; functionally low impact (REST
   works), but leaves the "REST + gRPC + Kafka" design only 1/3 realized.
5. **Transaction TRANSFER** — relatively small; add the `TRANSFER` type and wallet-to-wallet
   semantics (the `walletId` field is already scaffolded).
6. **OpenAPI/Swagger** — in charter, 0%, and the cheapest item to close; do it early.
7. **In-service audit logging** — open (see §1.1).
8. **Notification Service** — pending scope reconciliation (see §1.1).

---

## 5. Production-readiness gaps (axis 2)

**Blocking (before any real deployment)**
- Merge the feature branch and get a **green CI run** — most hardening is uncommitted and
  unverified by the build.
- Replace the **shared symmetric HMAC secret** with asymmetric signing (RS256/ES256): today
  every service can *mint* tokens, not just verify. Single biggest structural risk.
- No real **deployment target** (Kubernetes/ECS manifests), no TLS/HTTPS, no managed
  secrets store (currently a local `.env`).
- Single shared MySQL instance = shared failure domain; no **backup/restore** strategy.

**Observability (partially addressed — see §0.5)**
- Correlation-ID propagation + ECS JSON logging: **done on gateway/dashboard/transaction**, pending on auth/user/budget. No distributed tracing yet.
- OpenAPI/Swagger: **piloted on transaction-service**; rollout to the other REST services pending.
- No metrics (Micrometer/Prometheus), no dashboards (Grafana), no alerting.

**Resilience & API**
- No edge **rate limiting**; auth endpoints are unthrottled (gateway has Redis dep but unused).
- No gateway max-request-body limit (custom proxy buffers full bodies in memory).
- No retries/circuit breakers; dashboard fan-out is strictly fail-fast.
- No token revocation/denylist for access tokens.
- No OpenAPI/Swagger contract; no consumer-driven contract tests for the BFF↔upstreams.

**Testing & process**
- No load/performance tests, no whole-stack automated E2E in CI, no security scanning
  (SAST/dependency/secret scanning).
- No staging environment, no rollback/runbook beyond the JWT rotation doc.

---

## 6. Work done in the recent hardening session

All code changes live on branch **`feature/ci-github-actions`**. The dashboard fix (item 6)
was built and verified live in Docker; the rest was implemented and statically reviewed but
**not compiled/tested locally** (no local Maven; CI is the intended gate).

1. **Production-readiness review (advisory).** Identified that observability and security
   were under-prioritized and that "95% complete" measured features, not production readiness.
2. **CI pipeline.** `.github/workflows/ci.yml` — matrix build/test of all six services on
   `pull_request` + push to `main`, JDK 21, Maven cache, `mvn verify` (unit + Testcontainers),
   Surefire reports on failure.
3. **Secrets & DB hardening.** Removed hardcoded JWT secret and `root`/`123456` DB password
   from `docker-compose.yml`; moved to a gitignored `.env` (+ `.env.example`). Added per-service
   least-privilege DB users and a JWT secret-rotation runbook (`docs/security/jwt-secret-rotation.md`).
4. **Health checks / startup reliability.** Actuator liveness/readiness in all six services;
   compose healthchecks; `depends_on: condition: service_healthy` (fixes the startup race).
5. **JWT validation parity.** Hardened all six validators to enforce HS512 + issuer
   (`finsight-auth`) + audience (`finsight-api`) + signature + expiry, matching the gateway.
   Added a `JwtServiceTest` per service (valid / wrong issuer / wrong audience / wrong algo / expired).
6. **Dashboard root-cause fix (built + verified live).** Proved `DASHBOARD_UPSTREAM_ERROR` /
   `SERVICE_TIMEOUT` is a faithful symptom of transaction-service being unreachable, not a
   security/routing/deserialization bug. Added real cause logging; excluded
   `UserDetailsServiceAutoConfiguration` (the "generated password" red herring). Verified end-to-end.

| Item | State | Verified? |
|---|---|---|
| CI workflow | committed (branch) | YAML structure only; no live CI run |
| Secrets/DB users | committed (branch) | `docker compose config` valid; not booted with new users |
| Health probes / compose | committed (branch) | live: stack healthy, readiness-gated |
| JWT parity + tests | committed (branch) | static review only; **not compiled/tested locally** |
| Dashboard fix | committed (branch) | **built + verified live in Docker** |

---

## 7. CV positioning (honest)

**Above typical intern-portfolio level**, provided it is described as a *finance-tracker MVP
with strong engineering hygiene* — not as the full Intelligence & Risk platform.

Genuine strengths to talk about: real microservice decomposition with an explicit BFF and
enforced rules; security depth (JWT algorithm pinning + issuer/audience, lockout, secret
externalization, least-privilege DB users, rotation runbook); operational maturity (Actuator
probes, Docker healthchecks, dependency gating, CI with Testcontainers); and a genuine,
evidence-based distributed-bug investigation verified at runtime (the strongest talking point).

**Suggested CV bullet:**
> Built a 6-service Spring Boot 4 / Java 21 finance platform (API gateway, JWT auth,
> transactions, budgets, dashboard BFF) with MySQL/Flyway/Redis, Dockerized with
> readiness-gated startup, GitHub Actions CI, and Testcontainers integration tests; hardened
> JWT validation (HS512 + issuer/audience) and per-service DB credentials, and diagnosed/fixed
> a distributed upstream-failure bug with live verification.

**Be honest in interview:** learning/portfolio project, not production-deployed; the
Intelligence & Risk half of the vision (Analytics/Insights/Anomaly/Risk, Kafka, gRPC) is not
yet built; load/scale and a real deployment target are absent.

---

## 8. Recommended next order

Cheap-and-high-leverage first, then the vision-defining work:

1. Green CI run on a merged branch (validates §6 items 2–5).
2. OpenAPI/Swagger — **in progress** (transaction-service piloted; roll out to the rest). See §0.5.
3. Correlation IDs + structured JSON logging — **in progress** (live on gateway/dashboard/transaction; roll out to auth/user/budget). See §0.5.
4. Prometheus + Grafana.
5. Reconcile the Notification scope conflict (§1.1), then build it if confirmed in-scope.
6. Kafka event backbone — **foundation done** (Phase 2.1, §0.6); next: consumers + outbox.
7. Risk Intelligence + Analytics services (Behavioral Insights, Anomaly Detection) — the vision.
8. gRPC for internal sync calls.
9. Transaction `TRANSFER` type; in-service audit logging.
10. RS256/JWKS migration; edge rate limiting.
