# Deploying FinSight — Path A (single VPS + HTTPS)

This is the **"demo on the internet"** path from
[`project-status.md` §10](../project-status.md): one VPS running the existing Docker Compose
stack, fronted by **Caddy** for automatic HTTPS. It turns the local stack into a public
`https://…` demo with the least operational cost. It is **not** a production-hardening guide
(no HA, no managed DB, no RS256) — those stay future-scoped.

The repo already ships everything you need:

| Artifact | Purpose |
|---|---|
| `docker-compose.yml` | the base stack (dev + prod share it) |
| `docker-compose.prod.yml` | prod overlay: adds Caddy + the SPA, un-publishes every other port, hardens Grafana |
| `docker/caddy/Caddyfile` | TLS reverse proxy: `/api/*` → gateway, everything else → SPA |
| `web/Dockerfile` + `web/nginx.conf` | build + serve the React SPA |
| `.env.example` | includes the prod vars (`FINSIGHT_DOMAIN`, …) |

> **Security model.** In prod, **only Caddy** is published to the host (80/443). Every other
> container (gateway, services, MySQL, Kafka, Redis, Prometheus, Grafana) is reachable **only
> on the internal Docker network**. This is enforced in `docker-compose.prod.yml` with
> `ports: !override []`, *not* by the host firewall — because Docker manipulates iptables and
> can publish past `ufw`. Requires Docker Compose **v2.24+** (for the `!override` tag).

---

## 0. Prerequisites

- A VPS with **≥ 4 GB RAM (8 GB recommended)** — the stack is 9 JVMs + MySQL + Kafka + Redis +
  Prometheus + Grafana. Don't attempt a 1 GB free tier.
- A **domain or subdomain** whose DNS **A record** points at the VPS's public IP.
- Docker Engine + Docker Compose v2.24+ on the VPS.

## 1. DNS

Create an **A record** for your domain (e.g. `finsight.example.com`) → the VPS public IP.
Verify it resolves before requesting a certificate:

```bash
dig +short finsight.example.com     # should print your VPS IP
```

## 2. Firewall

Open **only** SSH and HTTP/HTTPS. Everything else stays on the Docker network.

```bash
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable
```

## 3. Get the code + secrets onto the box

```bash
git clone https://github.com/Trieu-hub/finsight-platform.git
cd finsight-platform
cp .env.example .env
```

Fill in `.env` (keep it private — `chmod 600 .env`). Generate strong values:

```bash
# JWT secret (>= 256-bit) and each DB password
openssl rand -base64 64 | tr -d '\n'      # JWT_SECRET
openssl rand -base64 24 | tr -d '/+=\n'   # each *_DB_PASSWORD, MYSQL_ROOT_PASSWORD, GF_SECURITY_ADMIN_PASSWORD
```

Required for prod (in addition to the dev vars):

```dotenv
FINSIGHT_DOMAIN=finsight.example.com
FINSIGHT_ACME_EMAIL=you@example.com
GF_SECURITY_ADMIN_PASSWORD=<strong-password>
```

> **Never commit `.env`.** It is gitignored. Optionally enable the AI features
> (`FINSIGHT_NARRATOR_AI_ENABLED` / `FINSIGHT_SUMMARIZER_AI_ENABLED` + `LLM_API_KEY`).

## 4. Launch

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

First boot builds all images and can take several minutes. Watch readiness:

```bash
docker compose ps
docker compose logs -f caddy          # certificate issuance
docker compose logs -f api-gateway
```

Caddy obtains a Let's Encrypt certificate automatically on first request to the domain.
When everything is healthy, open **`https://finsight.example.com`** → register → log in.

## 5. Verify the exposure is correct

From your laptop (NOT the VPS), confirm the internal ports are **not** reachable:

```bash
curl -sS -m 5 http://finsight.example.com:8080/actuator/health   # should FAIL / time out
curl -sSI https://finsight.example.com/                          # should be 200 (the SPA)
```

Only 80/443 should answer. If `:8080` responds, a port is still published — check that you
included `-f docker-compose.prod.yml`.

## 6. Observability (kept private)

Grafana and Prometheus are **not** published in prod. Reach Grafana over an SSH tunnel:

```bash
ssh -L 3000:127.0.0.1:3000 user@vps     # then open http://localhost:3000
```

Wait — Grafana is on the Docker network, not on the host's 127.0.0.1. To tunnel, temporarily
publish it to localhost only, or run `docker compose exec`/`docker inspect` to reach it. The
simplest option for a demo is to leave dashboards internal and screenshot them from an SSH
session. Log in with `admin` / `GF_SECURITY_ADMIN_PASSWORD` (anonymous admin is disabled).

## 7. Backups

Snapshot the MySQL volume nightly (cron). Minimal dump of all app databases:

```bash
docker compose exec mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --all-databases' > backup-$(date +%F).sql
```

Keep a few rotations off-box.

## 8. Updating

```bash
git pull
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

Flyway applies any new migrations on service startup; the named volumes persist data.

---

## What this Path A deploy does and does NOT cover

**Covered:** public HTTPS URL, TLS auto-renew, single-origin reverse proxy, only-Caddy-exposed
network, Grafana hardened, nightly backup, one-command update.

**Deliberately out of scope** (see `project-status.md` §6 / §10 — future work / interview talking
points): RS256/JWKS instead of the shared HMAC secret, edge rate limiting, transactional outbox,
managed/replicated MySQL, distributed tracing, Prometheus alerting, and any multi-host / HA
topology. For "production-grade", that is Path B.

## Optional: publish images to a registry (GHCR)

Path A builds images on the VPS from source, which needs no registry. If you would rather pull
prebuilt images (faster VPS boots, reproducible releases), add a GitHub Actions job that builds
and pushes each `services/*` and `web/` image to `ghcr.io/<owner>/finsight-<name>`, then replace
the `build:` keys in a compose overlay with `image:` references. This is listed as an optional
enhancement in the roadmap, not a requirement for the demo.
