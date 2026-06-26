# FinSight — Operations Runbook (local stack)

_Last updated: 2026-06-14 · Scope: the Docker Compose dev stack in this repo. Not a production
deployment guide (there is no production target yet)._

## Stack at a glance

| Component | Host port | Notes |
|---|---|---|
| api-gateway | 8080 | edge entrypoint |
| auth-service | 8081 | |
| user-service | 8082 | |
| transaction-service | 8083 | Kafka producer |
| budget-service | 8084 | Kafka producer + consumer |
| dashboard-service | 8085 | BFF, no DB |
| risk-service | _(not published)_ | listens on 8086 inside the network; Kafka consumer + producer; risk/insight/anomaly read APIs |
| notification-service | 8087 | Kafka consumer of RiskDetected; in-app notification read/mark-read API |
| Prometheus | 9090 | |
| Grafana | 3000 | anonymous admin (dev only) |
| MySQL / Redis / Kafka | _(not published)_ | reachable only on the compose network |

MySQL, Redis, Kafka, and **risk-service** deliberately do **not** publish host ports — risk-service
because it has no auth and must stay reachable only on the compose network (SE-2). Inspect them via
`docker compose exec` or by temporarily adding a `ports:` mapping.

---

## 1. Startup steps

**Prerequisites:** Docker + Docker Compose. (For running tests directly, also a JDK and the
`./mvnw` wrapper; integration tests need Docker for Testcontainers.)

1. **Create the secrets file** (gitignored):
   ```bash
   cp .env.example .env
   ```
2. **Fill in `.env`.** All values are required — compose uses `${VAR:?...}` and refuses to start
   if any are missing. Generate strong values:
   ```bash
   # JWT_SECRET must be >= 64 bytes (HS512) or every service fails to start
   openssl rand -base64 64 | tr -d '\n'      # -> JWT_SECRET
   openssl rand -base64 24 | tr -d '/+=\n'   # -> each *_DB_PASSWORD and MYSQL_ROOT_PASSWORD
   ```
   Required keys: `JWT_SECRET`, `MYSQL_ROOT_PASSWORD`, `AUTH_DB_PASSWORD`, `USER_DB_PASSWORD`,
   `TRANSACTION_DB_PASSWORD`, `BUDGET_DB_PASSWORD`, `RISK_DB_PASSWORD`.
3. **Validate the compose file** (catches missing env early):
   ```bash
   docker compose config >/dev/null && echo OK
   ```

---

## 2. Docker Compose workflow

```bash
# Build images and start the whole stack
docker compose up --build -d

# Watch startup ordering (services are readiness-gated via healthchecks + depends_on)
docker compose ps
docker compose logs -f api-gateway

# Tail one service
docker compose logs -f risk-service

# Stop (keep volumes / data)
docker compose down

# Stop and wipe MySQL/Prometheus/Grafana volumes (full reset)
docker compose down -v

# Rebuild a single service after a code change
docker compose up --build -d risk-service
```

Startup is **readiness-gated**: each service's healthcheck calls
`/actuator/health/readiness`, and `depends_on: condition: service_healthy` enforces ordering
(MySQL/Kafka before producers/consumers; all upstreams before dashboard; everything before the
gateway). First boot includes MySQL init (`docker/mysql/init/`) creating the five databases and
per-service least-privilege users, plus Flyway migrations per service.

**Health checks (from the host):**
```bash
# risk-service (8086) is not host-published — check it with `docker compose ps`
# (its container healthcheck) or `docker compose exec risk-service curl ...`.
for p in 8080 8081 8082 8083 8084 8085; do
  echo -n "$p "; curl -fsS http://localhost:$p/actuator/health/readiness && echo
done
```

---

## 3. Kafka verification

Kafka has no published host port; run commands inside the broker container (`finsight-kafka`).

```bash
# List topics — expect the three FinSight topics to appear once producers have run
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
# finsight.transactions.created
# finsight.budgets.changed
# finsight.risk.detected

# Tail a topic from the beginning (Ctrl-C to stop)
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic finsight.transactions.created --from-beginning

# Consumer group lag (budget-service / risk-service)
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group budget-service
```

**End-to-end smoke:** create a transaction (`POST /api/v1/transactions` through the gateway with
a valid JWT), then confirm a `TransactionCreated` record appears on
`finsight.transactions.created`, the budget consumer increments `spent_amount`, and — for a
qualifying expense — a `RiskDetected` record appears on `finsight.risk.detected`.

---

## 4. Prometheus verification

Open <http://localhost:9090>.

- **Targets:** Status → Targets — all eight service jobs (`api-gateway`, `auth-service`,
  `user-service`, `transaction-service`, `budget-service`, `dashboard-service`, `risk-service`,
  `notification-service`) plus `prometheus` should be **UP**.
- **Sample queries:**
  ```promql
  finsight_risk_events_processed_total
  finsight_risk_events_detected_total
  finsight_insights_generated_total
  finsight_anomalies_detected_total
  finsight_budget_events_processed_total
  ```
- Scrape path/target config lives in `docker/prometheus/prometheus.yml` (15s interval).

(Note: Micrometer dot-names are exported with underscores and a `_total` suffix for counters,
e.g. `finsight.risk.events.detected` → `finsight_risk_events_detected_total`.)

---

## 5. Grafana verification

Open <http://localhost:3000> (anonymous admin in the dev stack — no login).

- **Datasource:** Connections → Data sources — **Prometheus** (`http://prometheus:9090`),
  provisioned and marked default.
- **Dashboards** (folder **FinSight**, auto-provisioned from
  `docker/grafana/provisioning/dashboards/`):
  - **FinSight Platform Overview** — request rate, 5xx rate, p95 latency, JVM heap, GC, CPU.
  - **FinSight Event Pipeline** — budget consumer processed / duplicate / ignored / failed.
  - **FinSight Risk** — detected risks by type and severity.
  - **FinSight Consumer Lag** — Kafka consumer lag by service / group / partition (see §6).

If a panel is empty, the underlying metric simply hasn't been produced yet — generate activity
(create transactions/budgets) and re-check.

---

## 6. Kafka consumer lag monitoring

Consumer lag — how far each consumer group trails the head of its topic — is the primary
event-pipeline SLI. It is exported **natively by the Kafka client** and bound to Micrometer (no
custom metric): Spring Boot's `KafkaMetricsAutoConfiguration` instruments the auto-configured
consumer factories, and risk-service's hand-built budget read-model factory attaches a
`MicrometerConsumerListener` explicitly (`KafkaConsumerConfig`), so **all three consumer groups**
report.

**Consuming services / groups:**

| Service (Prometheus `job`) | Consumer group (`client_id` prefix) | Topic |
|---|---|---|
| budget-service | `consumer-budget-service` | `finsight.transactions.created` |
| risk-service | `consumer-risk-service` | `finsight.transactions.created` |
| risk-service | `consumer-risk-service-budgets` | `finsight.budgets.changed` |

**Exported metrics** (gauges; Prometheus adds `job` + `instance`):

| Metric | Meaning | At idle |
|---|---|---|
| `kafka_consumer_fetch_manager_records_lag` | latest lag of an assigned partition | `0` (numeric) |
| `kafka_consumer_fetch_manager_records_lag_max` | max lag since the last fetch | `NaN` until a fetch returns records |
| `kafka_consumer_fetch_manager_records_lag_avg` | avg lag since the last fetch | `NaN` until a fetch returns records |

Labels: `job`, `client_id`, `topic`, `partition`, `kafka_version`, `spring_id`. There is **no**
consumer-group label — the group is identified by the `client_id` prefix.

**Two gotchas (both already handled in the dashboard):**

1. **Use `records_lag`, not `records_lag_max`/`_avg`.** The `_max`/`_avg` variants read `NaN` on an
   idle/empty topic and would render as "No data"; `records_lag` (latest) is `0` at idle and rises
   with backlog.
2. **Deduplicate the dotted/underscore topic.** Kafka emits each partition **twice** — once with the
   real topic name (`finsight.transactions.created`) and once with dots replaced by underscores
   (`finsight_transactions_created`, deprecated). Any `sum()` therefore double-counts; filter to the
   canonical series with `{topic=~".+[.].+"}` (every FinSight topic contains a dot). Verified live:
   `count(records_lag)` is `6` unfiltered vs `3` filtered (the three real partitions).

**Dashboard:** **FinSight Consumer Lag** (folder FinSight), provisioned from
`docker/grafana/provisioning/dashboards/finsight-consumer-lag.json` — current max/total lag, max lag
by service, max lag by consumer group, and a per-partition drill-down. **No extra Prometheus scrape
config is needed**: the existing per-service jobs already expose these series.

**Verify manually:**
```bash
# Exact series from a service's scrape endpoint (risk-service has no host port):
docker compose exec risk-service   curl -s localhost:8086/actuator/prometheus | grep records_lag
docker compose exec budget-service curl -s localhost:8084/actuator/prometheus | grep records_lag

# Deduplicated total lag, exactly as the dashboard queries it:
curl -s http://localhost:9090/api/v1/query \
  --data-urlencode 'query=sum(kafka_consumer_fetch_manager_records_lag{topic=~".+[.].+"})'

# Cross-check against Kafka's own group view:
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group risk-service
```

**Troubleshooting:**

| Symptom | Likely cause | Action |
|---|---|---|
| No lag series for a group | the consumer has no partition assignment yet (topic absent / no producer has run) | create the topic or produce one event; series appear after assignment |
| `records_lag_max` shows "No data" / NaN | expected on an idle/empty topic | use `records_lag` (the dashboard already does) |
| Total lag looks ~2× too high | querying without the dotted-topic filter (the duplicate `_`-topic series) | add `{topic=~".+[.].+"}` |
| Lag climbing and not draining | a consumer is stuck / slow / erroring | check service logs; after retries risk/budget increment `finsight_*_events_failed_total` and skip (no DLT) |
| Series vanished for a live group | consumer crashed/unassigned (lag stops exporting rather than spiking) | also alert on **absence** (e.g. `kafka_consumer_coordinator_assigned_partitions`), not only high lag |

---

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `compose up` aborts: `set JWT_SECRET in .env` (or similar) | a required `.env` key is empty | fill every key in `.env`; re-run `docker compose config` |
| A service restarts / readiness stays DOWN | `JWT_SECRET` shorter than 64 bytes (HS512), or DB/Kafka not yet healthy | use a ≥64-byte secret; check `docker compose logs <svc>` and that `mysql`/`kafka` are healthy |
| `dashboard-service` returns `DASHBOARD_UPSTREAM_ERROR` / `SERVICE_TIMEOUT` | an upstream (user/transaction/budget) is unreachable or unready | check those services' readiness; the dashboard is strictly fail-fast |
| Kafka topics missing from `--list` | no producer has run yet, or a producer can't reach the broker | create a transaction/budget; verify `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` and broker health |
| `spent_amount` not updating | event was non-EXPENSE, had a null/bad date, or no budget matched (user+category+exact currency+date-in-window) | check `finsight_budget_events_ignored_total{reason=...}`; matching is currency-exact |
| Consumer group lag climbing | a consumer is failing to process | check the service logs; budget-service retries twice then increments `finsight_budget_events_failed_total` and skips (no DLT) |
| Prometheus target DOWN | service not healthy, or scrape endpoint blocked | confirm `/actuator/prometheus` returns 200 for that service |
| Port already in use on `up` | a host port (8080–8085, 9090, 3000) is taken | stop the conflicting process or remap the `ports:` entry |

---

## 8. Common local-development notes

- **Build/verify** a single service with the wrapper (no system Maven needed):
  ```bash
  cd services/<service> && ./mvnw verify
  ```
  Integration tests spin up **Testcontainers** (MySQL, and Kafka for the event E2E tests), so
  **Docker must be running**.
- **`finsight.kafka.enabled`** gates all Kafka wiring. It defaults to `true` (compose/local with
  a broker) and is `false` in the test profile, so MySQL-only tests never block on broker
  metadata. The Kafka E2E tests flip it back on against a Testcontainers broker.
- **risk-service is internal:** it has no JWT stack and is not behind the gateway, and its port is
  **not published to the host** (SE-2). Its read APIs (`/api/v1/risks`, `/api/v1/insights`,
  `/api/v1/anomalies`) are reachable only on the compose network at `risk-service:8086` (e.g. via
  `docker compose exec`), unauthenticated by design (admin/internal surface).
- **Schema is Flyway-owned** (`ddl-auto: validate`); never hand-edit tables. A new schema change
  is a new `V{n}__*.sql` migration. Wipe with `docker compose down -v` to replay migrations from
  scratch.
- **Secrets** live only in `.env` (gitignored). For JWT secret rotation see
  [security/jwt-secret-rotation.md](security/jwt-secret-rotation.md).
- **Dev-stack security posture** (anonymous Grafana, unauthenticated scrape/read endpoints,
  shared HMAC secret, no TLS) is acceptable locally and **not** a production posture.
