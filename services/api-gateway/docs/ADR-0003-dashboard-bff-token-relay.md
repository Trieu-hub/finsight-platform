# ADR-0003 — Dashboard BFF & cross-service token relay

- Status: **Accepted**
- Date: 2026-06-06
- Scope: the `dashboard-service` (read-only aggregation layer / backend-for-frontend)
  and the cross-service call pattern it introduces.
- Relates to: ADR-0001 (gateway architecture), ADR-0002 (auth contract).

## Context

Every existing FinSight service is self-contained: services do **not** call each other
at runtime; the only coupling is the shared JWT secret. The next milestone is a
**Dashboard** that presents aggregated read views (e.g. budget spent-vs-limit, which
budget-service deliberately does not compute). Aggregation inherently requires reading
from Transaction, Budget and User — so the Dashboard must make cross-service calls.

## Decision

1. **Introduce `dashboard-service` as a read-only BFF that owns no data and no database.**
   It composes responses by calling the other services' existing REST APIs. This is the
   **single sanctioned exception** to the "no runtime cross-service calls" rule; the core
   business services remain non-calling.
2. **Direct service-to-service calls** over the compose network (`http://<service>:<port>`),
   not back through the gateway — avoids a routing hairpin for an internal consumer.
3. **Token relay (passthrough).** The Dashboard forwards the caller's `Authorization:
   Bearer <jwt>` unchanged on every outbound call. Each upstream validates the token and
   scopes data to that user (`userId` from the JWT). No service account, no new secret —
   reuses the shared HMAC anchor (ADR-0002).
4. **Service-level validation is preserved.** The Dashboard also validates the JWT locally
   (like every service); the gateway validating at the edge does not remove this.
5. **Fail-fast.** Any upstream timeout/error → `502 DASHBOARD_UPSTREAM_ERROR` in the
   standard error envelope. A 404 from user-service (no profile yet) is treated as a
   normal "absent profile", not a failure. Per-call connect/read timeouts are configured.
6. **Gateway routing.** `/api/v1/dashboard/**` is routed to `dashboard-service:8085` and is
   an authenticated route (not on the public allow-list, ADR-0002 §4).

## Consequences

- **Availability coupling:** the Dashboard is only as available as its upstreams;
  mitigated by timeouts + the explicit fail-fast contract. Partial-degradation was
  considered and deferred (keeps v1 predictable).
- **Latency:** `overview` fans out to three services sequentially; parallelizing is a
  future optimization, intentionally not done now.
- **No new infrastructure:** no database, no Redis, no message broker. The Dashboard is
  removable without affecting any other service.

## Explicitly rejected / out of scope

CQRS, event sourcing, Kafka/RabbitMQ, a Dashboard-owned read-model/cache database,
distributed transactions, and the gateway-hairpin call path. These add complexity not
justified by current requirements.
