# FinSight — Project Status

_Last updated: 2026-06-14_
_Repo: `D:\finsight` · Branch: `feature/ci-github-actions`_

FinSight's **original vision** is a *Financial Intelligence & Risk Monitoring Platform* —
explicitly **not** just an expense tracker. This document measures progress against that
chartered scope, not against the current repository alone.

**Stack (today):** Java 21 · Spring Boot 4.0.6 · Spring Security · Spring Data JPA · Flyway ·
MySQL 8 · Redis · Kafka (KRaft) · JWT (HS512) · springdoc/OpenAPI · Micrometer ·
Prometheus · Grafana · Docker Compose · GitHub Actions · Testcontainers.

> Companion docs (added in this pass): [docs/architecture.md](docs/architecture.md),
> [docs/event-catalog.md](docs/event-catalog.md), [docs/intelligence.md](docs/intelligence.md),
> [docs/runbook.md](docs/runbook.md).

---

## 0. Progress at a glance — three axes (not one number)

A single "% complete" mixes very different goals. Progress is tracked on three independent axes:

| Axis | What it measures | Progress |
|---|---|---|
| **MVP backend** | Core finance CRUD + auth + dashboard working end-to-end | **~90%** |
| **Production-ready MVP** | The MVP, operable & secure for real deployment | **~72%** |
| **Full FinSight vision** | The chartered Intelligence & Risk platform | **~65%** |

**Headline:** The MVP (finance tracker + budgets + dashboard) is built, and the platform now
has a **real multi-topic event backbone** (3 topics, 2 producers, 2 idempotent consumers) plus
**full Prometheus/Grafana observability** and **OpenAPI docs**. The product's defining half —
*Intelligence & Risk* — is **no longer at zero**: risk-service implements rule-based
**Risk Monitoring**, **Behavioral Insights**, and **Anomaly Detection** end-to-end (Phases D–F).
The remaining vision gaps are **gRPC** (0%), a **Notification Service** (0% — `RiskDetected` has
no consumer), and a deeper **Analytics** engine.

---

## 1. Phase completion (implemented & verified in code)

All phases below are **DONE** — implemented, with unit + Testcontainers integration tests.

| Phase | Scope | Evidence in code |
|---|---|---|
| **A** | OpenAPI/Swagger rollout (springdoc 3.0.3) to auth, user, budget, dashboard (transaction piloted) | `OpenApiConfig` + SecurityConfig permit-list + `/v3/api-docs` test per service |
| **2.1** | transaction-service **produces** `TransactionCreated` (AFTER_COMMIT, keyed by userId) | `event/` package; topic `finsight.transactions.created` |
| **2.2** | budget-service **consumes** `TransactionCreated` → materializes `spent_amount`; idempotency inbox | `TransactionEventConsumer`, `processed_events`; ADR-0004 |
| **C.1** | Micrometer → Prometheus → Grafana; datasource + **Platform Overview** dashboard | compose `prometheus`/`grafana`; `docker/prometheus`, `docker/grafana` |
| **C.2** | `finsight.budget.events.failed` (retry-exhaustion) + **Event Pipeline** dashboard | `KafkaConsumerConfig` recoverer; `finsight-event-pipeline.json` |
| **D.1** | risk-service foundation: consume `TransactionCreated`, `HIGH_AMOUNT_EXPENSE`, **produce** `RiskDetected` | `RiskEventConsumer`, `RiskDetectedEvent`; topic `finsight.risk.detected` |
| **D.2** | Persist detections to `risk_alerts`; read API `GET /api/v1/risks` | `RiskAlert`, `RiskAlertService`, `RiskAlertController` |
| **D.3** | Detection metrics `finsight.risk.events.detected{type,severity}` + **Risk** dashboard | `RiskEventConsumer`; `finsight-risk.json` |
| **D.4** | `observed_expenses` read-model + windowed rules `RAPID_SPENDING`, `LARGE_DAILY_SPEND` | `RiskRuleEngine`, `ObservedExpenseRepository` |
| **E.1** | Behavioral insight `SPENDING_INCREASE`; `insights` table; `GET /api/v1/insights`; `finsight.insights.generated` | `InsightService`, `Insight`, `InsightController` |
| **E.2** | `CATEGORY_SURGE` + `BUDGET_RISK`; budget read-model from **`BudgetChanged`** (budget-service produces it) | `BudgetEventConsumer`, `budget_snapshots`; `KafkaBudgetEventPublisher` |
| **E.3** | `LOW_SAVINGS_RATE`; INCOME recorded into `observed_expenses` (income side) | `InsightService`, V7 migration |
| **F.1** | Anomaly `UNUSUAL_TRANSACTION_AMOUNT`; `anomalies` table; `GET /api/v1/anomalies`; `finsight.anomalies.detected` | `AnomalyService`, `Anomaly`, `AnomalyController`, V8 migration |

Detailed trigger conditions, severities, persistence, and metrics for D–F are in
[docs/intelligence.md](docs/intelligence.md).

---

## 2. Chartered scope vs. reality

### Core domains (5)

| Core domain | Status | Notes |
|---|---|---|
| Personal Finance Management | ✅ ~85% | Transactions, categories, budgets |
| Dashboard & Analytics | ⚠️ partial | Dashboard ✅; a dedicated *Analytics* engine is still ~30% |
| Behavioral Insights | ✅ MVP | Rule-based: SPENDING_INCREASE, CATEGORY_SURGE, BUDGET_RISK, LOW_SAVINGS_RATE (E.1–E.3) |
| Anomaly Detection | ✅ MVP | Rule-based: UNUSUAL_TRANSACTION_AMOUNT (F.1) |
| Financial Risk Monitoring | ✅ MVP | Rule-based: HIGH_AMOUNT_EXPENSE, RAPID_SPENDING, LARGE_DAILY_SPEND (D.1–D.4) |

The three intelligence domains now have **working rule-based implementations** (no ML — and ML
is explicitly out of scope for v1). The remaining depth (richer analytics, more rules) is
incremental, not greenfield.

### Services (8 chartered)

| Service (charter) | Reality | Mapping / completeness |
|---|---|---|
| API Gateway | ✅ exists | Proxy + edge JWT validation; **no rate limiting** → ~80% |
| Auth Service | ✅ exists | JWT, refresh, lockout, Redis-backed → ~90% |
| User Service | ✅ exists | Profile data → ~85% |
| Transaction Service | ✅ exists | INCOME/EXPENSE + categories/summaries; **no TRANSFER** → ~75% |
| Budget Service | ✅ exists | Definitions + event-driven utilization → ~85% |
| **Analytics Service** | ⚠️ substituted | `dashboard-service` (BFF) covers presentation, not analysis → ~30% |
| **Risk Intelligence Service** | ✅ exists | `risk-service`: Risk + Insights + Anomaly (rule-based MVP) → ~70% |
| **Notification Service** | ❌ absent | 0% — `RiskDetected` is published but has **no consumer** yet (see §5.1) |

`dashboard-service` is an **extra** BFF, not one of the 8 chartered services.

### Communication pillars (3)

| Pillar | Charter | Reality |
|---|---|---|
| REST (external) | required | ✅ implemented |
| gRPC (internal sync) | required | ❌ **0%** — no proto, no deps; internal calls are REST (`RestClient`) |
| Kafka (async events) | required | ✅ **real backbone** — 3 topics, 2 producers (`TransactionCreated`, `BudgetChanged`), 2 idempotent consumers (budget, risk), 1 best-effort output (`RiskDetected`). See [docs/event-catalog.md](docs/event-catalog.md) |

### Infrastructure

| Item | Charter | Reality |
|---|---|---|
| MySQL (DB-per-service) | ✅ | ✅ five logical DBs (auth/user/transaction/budget/risk) on one instance |
| Redis | ✅ | ⚠️ only auth-service uses it (refresh tokens + lockout); no other service depends on Redis (the gateway has **no** Redis dependency) |
| Kafka | ✅ | ✅ single-node KRaft broker; full producer→consumer flows (Phases 2.1–F.1) |
| Docker | ✅ | ✅ |
| CI/CD | ✅ | ✅ GitHub Actions (build + test) |
| OpenAPI/Swagger | ✅ | ✅ Phase A on the 5 user-facing REST services (auth/user/transaction/budget/dashboard); gateway + internal risk-service excluded |
| Monitoring (Prometheus/Grafana) | ✅ | ✅ Phase C — scrape of all 7 services + 3 provisioned dashboards |

### Explicitly out-of-scope for v1 (correctly absent)

Saga · CQRS · Event Sourcing · ELK · Keycloak · Istio · Helm · ArgoCD · ML · Blockchain ·
**separate Audit Service**. None of these count against progress.

---

## 3. Architecture as built (what exists today)

| Service | Port | Owns DB | Responsibility |
|---|---|---|---|
| api-gateway | 8080 | – | Edge routing + JWT validation (HS512/iss/aud), error envelope |
| auth-service | 8081 | `auth_db` | Register, login, refresh, account lockout; Redis-backed tokens |
| user-service | 8082 | `user_db` | User profile data |
| transaction-service | 8083 | `transaction_db` | Transactions (INCOME/EXPENSE), categories, summaries; produces `TransactionCreated` |
| budget-service | 8084 | `budget_db` | Budget definitions + utilization; consumes `TransactionCreated`, produces `BudgetChanged` |
| dashboard-service | 8085 | none (BFF) | Aggregates user + transaction + budget; fail-fast; relays JWT |
| risk-service | 8086 | `risk_db` | Risk rules, insights, anomaly; consumes `TransactionCreated` + `BudgetChanged`, produces `RiskDetected`; read APIs |
| mysql / redis / kafka | – | – | Shared MySQL (DB-per-service) + Redis (auth) + single-node KRaft broker |
| prometheus / grafana | 9090 / 3000 | – | Metrics scrape + dashboards |

**Design rules:** no runtime cross-service calls between business services (only the dashboard
BFF calls others); all other coupling is Kafka; `userId` read only from the JWT; Flyway owns the
schema (`ddl-auto: validate`); shared HMAC secret; gateway is removable (services validate
tokens independently); risk-service is internal (no JWT stack, not behind the gateway).

Full diagrams: [docs/architecture.md](docs/architecture.md).

---

## 4. Capabilities accomplished

- End-to-end auth: register → login → JWT (HS512) → authorized calls through the gateway, with
  per-account lockout and Redis-backed token/session data.
- Full CRUD + reporting in transaction and budget services (summaries, trends, utilization).
- A stateless BFF aggregating three services with fail-fast behavior and JWT relay.
- **A real Kafka backbone:** `TransactionCreated` and `BudgetChanged` produced AFTER_COMMIT;
  budget-service materializes utilization idempotently; risk-service derives risk/insight/anomaly.
- **Rule-based intelligence MVP** in risk-service across all three domains (Phases D–F), each
  persisted and exposed over a read API, each emitting Micrometer metrics.
- Observability: every service scraped by Prometheus; 3 provisioned Grafana dashboards
  (Platform Overview, Event Pipeline, Risk); ECS JSON logging with correlation IDs.
- OpenAPI/Swagger on every REST service.
- Flyway-owned schemas, `validate`-only Hibernate, DB-per-service isolation.
- Containerized stack with readiness-gated startup; CI with Testcontainers integration tests.

---

## 5. Remaining roadmap (separate from completed work)

**Vision-defining (the chartered "second half"):**
1. **Notification Service** — consume `RiskDetected` and deliver alerts. The topic and producer
   already exist; only the consumer/delivery side is missing. (Scope note in §5.1.)
2. **gRPC (internal sync)** — architectural pillar at 0%; no proto, no deps.
3. **Analytics engine** — distinct from the dashboard's presentation; deeper rollups/analysis.
4. **More intelligence rules** — incremental additions on the existing risk-service framework
   (explicitly **not** part of this documentation pass; e.g. Phase F.2 is **not** started).

**Product features:**
5. **Transaction `TRANSFER`** type + wallet-to-wallet semantics (`walletId` is scaffolded).
6. **In-service audit logging** — only JPA timestamp auditing exists today (see §5.1).

**Production-readiness (axis 2):**
7. Merge the feature branch and get a **green CI run** (JDK 21).
8. Replace the **shared symmetric HMAC secret** with asymmetric signing (RS256/ES256) — today
   every service can *mint* tokens, not just verify. Biggest structural risk.
9. Real **deployment target** (K8s/ECS), TLS/HTTPS, managed secrets store.
10. Edge **rate limiting**; gateway max-request-body limit; retries/circuit breakers.
11. **Transactional outbox** to close the AFTER_COMMIT dual-write gap (ADR-0004).
12. Distributed tracing; Prometheus **alerting** rules; backup/restore for the shared MySQL.
13. Load/performance tests; whole-stack E2E in CI; SAST/dependency/secret scanning.

### 5.1 Scope clarifications (unchanged)

- **Audit:** the charter keeps audit as *in-service logging*, with a separate Audit Service only
  "if time allows." A missing Audit *Service* is not a gap; but the real requirement — action/
  security audit logging inside each service — is **not implemented** (only JPA timestamp
  auditing). Item is *open*.
- **Notification — charter conflict:** listed in the chartered architecture (1 of 8 services) and
  not in the v1 out-of-scope list, so by the written charter it is **required**. `RiskDetected`
  is already produced; building the consumer/delivery side resolves this.

---

## 6. Production-readiness gaps (axis 2)

**Blocking (before any real deployment)**
- Green CI run on a merged branch.
- Asymmetric JWT signing (RS256/ES256) — replace the shared HMAC secret.
- No deployment target (K8s/ECS), no TLS/HTTPS, no managed secrets store (local `.env`).
- Single shared MySQL = shared failure domain; no backup/restore strategy.

**Observability — now substantially addressed**
- ✅ Micrometer/Prometheus across all 7 services; ✅ 3 Grafana dashboards; ✅ ECS JSON logging +
  correlation IDs (on api-gateway/transaction/dashboard).
- ⚠️ Correlation-ID/ECS rollout to auth/user/budget pending; no distributed tracing; **no alerting**.

**Resilience & API**
- No edge rate limiting; auth endpoints unthrottled (the gateway has no Redis dependency to back a limiter yet).
- No gateway max-request-body limit; no retries/circuit breakers (dashboard is fail-fast).
- No token revocation/denylist for access tokens.
- Kafka delivery is at-least-once with an accepted AFTER_COMMIT dual-write gap (no outbox).

**Testing & process**
- No load/performance tests, no whole-stack E2E in CI, no security scanning.
- No staging environment; runbook now exists ([docs/runbook.md](docs/runbook.md)) but no
  rollback automation.

---

## 7. CV positioning (honest)

A **6→7-service Spring Boot 4 / Java 21 platform** with real microservice decomposition, an
explicit BFF, a working **Kafka event backbone** (idempotent consumers, read-models), a
**rule-based intelligence layer** (risk/insights/anomaly), and an **observability stack**
(Prometheus/Grafana, structured logging) — described as a *finance-intelligence MVP with strong
engineering hygiene*, not a production-deployed, ML-driven platform.

Genuine strengths: enforced service boundaries; security depth (JWT algorithm pinning +
issuer/audience, lockout, secret externalization, least-privilege DB users, rotation runbook);
event-driven design with idempotency and documented tradeoffs (ADR-0004); and operational
maturity (Actuator probes, Docker healthchecks, CI with Testcontainers, dashboards).

**Be honest in interview:** learning/portfolio project, not production-deployed; intelligence is
**rule-based, not ML**; gRPC and a Notification Service are not built; load/scale and a real
deployment target are absent.

---

## 8. Recommended next order

1. Green CI run on a merged branch.
2. Build the **Notification Service** (consume `RiskDetected`) — closes the last chartered
   service and gives `RiskDetected` a consumer.
3. Analytics engine (distinct from the dashboard BFF).
4. gRPC for internal sync calls.
5. Transaction `TRANSFER` type; in-service audit logging.
6. RS256/JWKS migration; edge rate limiting; transactional outbox.
7. Distributed tracing + Prometheus alerting.
