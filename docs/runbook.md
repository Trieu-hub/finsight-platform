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
| notification-service | 8087 | Kafka consumer of RiskDetected + BudgetExceeded; in-app notification read/mark-read API; delivery channels (SSE, web push, email, signed webhook) and the digest scheduler |
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
   # RS256 keypair — prints JWT_PRIVATE_KEY and JWT_PUBLIC_KEY; paste both into .env
   ./scripts/gen-jwt-keys.sh
   openssl rand -base64 24 | tr -d '/+=\n'   # -> each *_DB_PASSWORD and MYSQL_ROOT_PASSWORD
   ```
   Required keys: `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`, `MYSQL_ROOT_PASSWORD`, `AUTH_DB_PASSWORD`,
   `USER_DB_PASSWORD`, `TRANSACTION_DB_PASSWORD`, `BUDGET_DB_PASSWORD`, `RISK_DB_PASSWORD`.
   `JWT_PREVIOUS_PUBLIC_KEYS` stays empty except while rotating the signing key.
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
# List topics — expect the five FinSight topics to appear once producers have run
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
# finsight.transactions.created
# finsight.budgets.changed
# finsight.budgets.exceeded
# finsight.risk.detected
# finsight.reports.monthly   (declared at analytics-service startup, published monthly)

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

**The two scheduled producers** publish on a clock, not on a write, so neither shows up in that
smoke test:

```bash
# Recurring series risk-service has recognised (internal API, no auth, not behind the gateway)
docker compose exec risk-service curl -s localhost:8086/api/v1/recurring

# Monthly reports already published (one row per user per month)
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  -e "SELECT user_id, period_month, sent_at FROM analytics_db.monthly_report_sent;"
```

**The monthly report sweep is OFF by default** (`FINSIGHT_REPORT_MONTHLY_ENABLED=false`): its
first run against a populated database mails every user who was active last month. Turn it on
when someone is there to watch the first send:

```bash
FINSIGHT_REPORT_MONTHLY_ENABLED=true docker compose up -d --force-recreate analytics-service
# on prod, set it in secrets.env instead:  sops set secrets.env '["FINSIGHT_REPORT_MONTHLY_ENABLED"]' '"true"'

# ALWAYS confirm the switch actually reached the process — `up -d` alone was observed
# leaving the old container in place, which looks exactly like "the feature is broken":
docker compose exec analytics-service printenv FINSIGHT_REPORT_MONTHLY_ENABLED
```

To exercise it without waiting for a month boundary, delete that user's `monthly_report_sent`
row and restart analytics-service with a tight cron
(`FINSIGHT_REPORT_MONTHLY_CRON='0/20 * * * * *'`); the sweep always targets the previous month.
With the flag off the topic is still declared and the consumer still runs — nothing is published.

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

### 5.1 Log aggregation (Loki)

The `monitoring` profile also starts **Loki** (log store, :3100) and **Promtail** (shipper).
Promtail discovers every `finsight-*` container through the Docker socket and pushes its stdout to
Loki; Grafana auto-provisions the **Loki** datasource alongside Prometheus and Tempo. This is the
third observability pillar — metrics (Prometheus), traces (Tempo), logs (Loki) — and, like the
other two, is **opt-in** and absent from a default prod deploy (it stays off there to save memory;
turn it on when an incident needs logs searchable instead of `docker logs` per container).

Verify in Grafana (**Explore** → datasource **Loki**):

```logql
{container="finsight-transaction-service"}          # one service's logs
{compose_service=~".+"}                              # everything Promtail is shipping
{service="transaction-service"} | json               # ECS-parsed fields (level, correlationId)
```

All nine services emit ECS JSON (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` in compose), so every line
carries `level`/`service` labels plus the `correlationId` MDC field — meaning one request can be
followed across services with a single query:

```logql
{compose_service=~".+"} | json | correlationId = "<id from the X-Correlation-ID response header>"
```

That query spans the **async** hops too, not just the HTTP ones: the id rides on an
`X-Correlation-ID` Kafka record header, so the budget/risk/analytics/notification lines produced
seconds later by consuming the event come back under the same id as the write that caused it. If a
consumer's lines are missing from the result, check that its `KafkaConsumerConfig` registers the
`RecordInterceptor` bean — without it that service logs under a fresh id per record.

Config lives in `docker/loki/loki.yml` and `docker/promtail/promtail.yml`; retention is 7 days on
the local filesystem.

**Troubleshooting:**

| Symptom | Likely cause | Action |
|---|---|---|
| No datasource / "No data" in Explore | started without `--profile monitoring` | bring the stack up with the profile so Loki + Promtail run |
| A container's logs never appear | Promtail can't read the Docker socket | confirm `/var/run/docker.sock` is mounted into `finsight-promtail`; check its logs |
| `{service=...}` matches nothing but `{container=...}` works | it is an infrastructure container (mysql, kafka, redis, caddy), which logs plain text | filter by `container`/`compose_service`; ECS labels only exist for the nine Spring services, which set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` |
| Promtail warns `400 … timestamp too old` on first start | it is backfilling a long-running container's old stdout past Loki's 7-day window | benign — Loki drops only those ancient lines; current logs ingest normally. Recreate the container to reset its stdout, or ignore |

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
| `compose up` aborts: `set JWT_PRIVATE_KEY in .env` (or similar) | a required `.env` key is empty | fill every key in `.env`; re-run `docker compose config` |
| A service restarts / readiness stays DOWN | `JWT_PUBLIC_KEY` malformed or not the pair of `JWT_PRIVATE_KEY`, or DB/Kafka not yet healthy | regenerate both with `./scripts/gen-jwt-keys.sh`; check `docker compose logs <svc>` and that `mysql`/`kafka` are healthy |
| `dashboard-service` returns `DASHBOARD_UPSTREAM_ERROR` / `SERVICE_TIMEOUT` | an upstream (user/transaction/budget) is unreachable or unready | check those services' readiness; the dashboard is strictly fail-fast |
| Kafka topics missing from `--list` | no producer has run yet, or a producer can't reach the broker | create a transaction/budget; verify `KAFKA_BOOTSTRAP_SERVERS=kafka:9092` and broker health |
| `spent_amount` not updating | event was non-EXPENSE, had a null/bad date, or no budget matched (user+category+exact currency+date-in-window) | check `finsight_budget_events_ignored_total{reason=...}`; matching is currency-exact |
| Consumer group lag climbing | a consumer is failing to process | check the service logs; budget-service retries twice then increments `finsight_budget_events_failed_total` and skips (no DLT) |
| Prometheus target DOWN | service not healthy, or scrape endpoint blocked | confirm `/actuator/prometheus` returns 200 for that service |
| Port already in use on `up` | a host port (8080–8085, 9090, 3000) is taken | stop the conflicting process or remap the `ports:` entry |
| Saving a webhook URL returns 400 `INVALID_WEBHOOK_URL` | by design: notification-service refuses anything that is not a **public https** address, because it is the server that connects, from inside the private network. `http://`, `localhost`, RFC 1918, CGNAT and cloud-metadata addresses are all refused — see `webhook/WebhookUrlValidator` | use a publicly reachable https endpoint; for local testing point it at a tunnel (ngrok/Cloudflare) rather than relaxing the validator |
| Webhook enabled but the receiver gets nothing | the alert may be sitting in a digest window, or the stored URL now fails re-validation | check `finsight_notifications_webhook_blocked_total` (URL refused at delivery time) and `..._failed_total`; if the user is on HOURLY/DAILY the delivery is due only once the **oldest** pending alert is older than the window, and the scheduler polls every 5 min (`DIGEST_POLL_MS`) |
| Receiver rejects the signature | it must verify HMAC-SHA256 over `"<t>.<body>"` — the raw body bytes, and the timestamp from the header included — not over the body alone | header is `X-Vernfy-Signature: t=<epoch>,v1=<hex>`; the secret is the one returned once when the URL was saved. Re-save the URL to mint a new one |
| Edited a bind-mounted config (`Caddyfile`, `prometheus.yml`, …) and the container still serves the old one — a reload even reports success | the file was replaced with `mv`, `sed -i`, `scp`, or an editor that writes-then-renames. A **single-file** bind mount follows the **inode**, not the path, so a new inode leaves the container on the old file | compare `stat -c %i <host path>` with `docker exec <c> stat -c %i <container path>`. Rewrite in place to keep the inode — `tr -d '\r' < f > /tmp/f && cat /tmp/f > f` — or force a fresh mount with `up -d --force-recreate <svc>` (plain `up -d` won't: the service definition hasn't changed) |

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
- **Secrets** live only in `.env` (gitignored). Rotating the JWT signing key is a no-downtime
  operation that restarts auth-service only — the other services rediscover the key through
  its JWK Set. See [security/jwt-key-rotation.md](security/jwt-key-rotation.md).
- **Dev-stack security posture** (anonymous Grafana, unauthenticated scrape/read endpoints,
  no TLS) is acceptable locally and **not** a production posture.
