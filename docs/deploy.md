# Deploying FinSight — Path A (single VPS + HTTPS)

This is the **"demo on the internet"** path from
[`project-status.md` §10](../project-status.md): one VPS running the existing Docker Compose
stack, fronted by **Caddy** for automatic HTTPS. It turns the local stack into a public
`https://…` demo with the least operational cost. It is **not** a production-hardening guide
(no HA, no managed DB, no JWKS/key rotation) — those stay future-scoped. JWT signing is already
**RS256 asymmetric** (auth-service holds the private key; every other service verifies with the
public key only), so this guide has you generate an RSA keypair rather than a shared secret.

The repo already ships everything you need:

| Artifact | Purpose |
|---|---|
| `docker-compose.yml` | the base stack (dev + prod share it) |
| `docker-compose.prod.yml` | prod overlay: adds Caddy + the SPA, un-publishes every other port, hardens Grafana |
| `docker-compose.oracle.yml` | free-tier overlay: memory trims for the Oracle Always Free 12 GB ARM box |
| `docker-compose.codespaces.yml` + `.devcontainer/` | free-tier: run the whole stack in a GitHub Codespace, public HTTPS URL, no card |
| `docker/caddy/Caddyfile` | TLS reverse proxy: `/api/*` → gateway, everything else → SPA |
| `web/Dockerfile` + `web/nginx.conf` | build + serve the React SPA |
| `.env.example` | includes the prod vars (`FINSIGHT_DOMAIN`, …) |

> **Security model.** In prod, **only Caddy** is published to the host (80/443). Every other
> container (gateway, services, MySQL, Kafka, Redis, Prometheus, Grafana) is reachable **only
> on the internal Docker network**. This is enforced in `docker-compose.prod.yml` with
> `ports: !override []`, *not* by the host firewall — because Docker manipulates iptables and
> can publish past `ufw`. Requires Docker Compose **v2.24+** (for the `!override` tag).

---

## Free on GitHub Codespaces (no card, runs in the cloud) — easiest fallback

If you **can't** rent a VPS and your laptop is too small to run the stack locally (it needs
~8 GB just for the containers), this is the quickest $0 path: run the whole stack **inside a
GitHub Codespace** and forward one port as a public HTTPS URL. It needs only a GitHub account —
**no credit card**.

> **Cost/quota.** Codespaces' free plan includes ~**120 core-hours + 15 GB-month** of storage.
> The stack needs the **4-core / 16 GB** machine (the 2-core default is too small), which burns
> 4 core-hours per running hour → ~**30 hours/month** free. It **auto-suspends after 30 min
> idle**, so you only spend hours while actively demoing. Without a payment method on your
> GitHub account, hitting the cap simply **stops** the Codespace — it cannot bill you.

This is **on-demand**, not 24/7: the URL is live only while the Codespace is running. For an
always-on free URL you still need Oracle (below).

### Steps
1. Make sure `.devcontainer/`, `docker-compose.codespaces.yml`, and
   `docker/caddy/Caddyfile.codespaces` are pushed to GitHub (they're in this repo).
2. On the repo page: **Code ▾ → Codespaces → Create codespace on `main`**. The devcontainer
   requests the 4-core/16 GB machine and auto-generates a throwaway `.env` (random secrets, AI
   off) via `postCreateCommand`.
3. In the Codespace terminal, launch:
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.codespaces.yml up -d --build
   ```
   First build takes several minutes. Watch with `docker compose ps`.
4. Open the **Ports** tab → row for port **80** → right-click → **Port Visibility → Public**.
   (If your org blocks public ports, leave it Private — the URL still works for anyone signed
   into GitHub via your shared link.) Click the globe icon to open
   `https://<codespace>-80.app.github.dev` → register → log in.
5. When done, **stop** the Codespace (Codespaces list → `…` → *Stop*) to save your hours.

Caddy is HTTP-only here (Codespaces terminates HTTPS at its port proxy); the SPA and API share
one origin so the relative `/api/v1` calls just work. No domain, no certificate, no `.env`
editing needed.

---

## Free hosting on Oracle Cloud "Always Free" ($0, ARM 2 OCPU / 12 GB)

This is the **zero-cost** way to run Path A. Oracle's Always Free tier gives an ARM
(Ampere A1) VM that is large enough for the whole stack — unlike the ~1 GB free tiers of
AWS/GCP/Azure, which cannot fit 9 JVMs + Kafka.

> **Will I be charged? No — if you never upgrade.** Per Oracle's docs, an Always Free account
> that has **never been upgraded to Pay As You Go cannot be charged money** ("free of charge …
> for the life of the account"). When you hit a limit, resources are **blocked / not created**,
> never billed. **Two rules to stay free:** (1) do **not** click *"Upgrade to Pay As You Go"*;
> (2) only pick shapes/volumes marked **"Always Free-eligible"**.

**Current Always Free limits** (reduced June 2026 from the old 4 OCPU / 24 GB):

| Resource | Free amount | Our use |
|---|---|---|
| ARM Ampere A1 compute | **2 OCPU + 12 GB RAM** | fits (~8 GB, see below) |
| Block storage | 200 GB | ~10 GB of images + volumes |
| Outbound transfer | 10 TB / month | trivial for a demo |

> **Reclaim note (not a concern here).** Oracle may delete an A1 instance only if CPU **and**
> network **and** memory are *all* below 20% for 7 days. This stack holds RAM at ~66%, so the
> AND-condition is never met — the box is never reclaimed. No keep-alive cron needed.

### O.1 Create the instance
1. Sign up at <https://cloud.oracle.com> (a card is required for identity verification;
   an Always Free account is **not** charged). **Do not upgrade to Pay As You Go.**
2. **Compute → Create Instance.** Choose **Ubuntu 22.04**, shape **VM.Standard.A1.Flex**,
   set **2 OCPU / 12 GB** (must show the "Always Free-eligible" chip). Boot volume default is
   fine. Upload/download your SSH key.

### O.2 Open the ports — BOTH layers (the classic Oracle gotcha)
Oracle Ubuntu images block everything but SSH at **two** independent layers. You must open
80/443 in **both**, or Caddy's certificate request and the site will silently fail:

1. **VCN Security List** (cloud firewall): Networking → your VCN → Security List → add
   **ingress** rules for TCP **80** and **443** from `0.0.0.0/0`.
2. **Instance iptables** (the image ships a restrictive INPUT chain). SSH in and insert ACCEPT
   rules *before* the existing REJECT, then persist:
   ```bash
   sudo iptables -L INPUT --line-numbers        # find the REJECT line number (call it N)
   sudo iptables -I INPUT N -p tcp --dport 443 -j ACCEPT
   sudo iptables -I INPUT N -p tcp --dport 80  -j ACCEPT
   sudo netfilter-persistent save
   ```
   On Oracle, use these iptables rules **instead of** the `ufw` block in §2 (the image manages
   iptables directly; running ufw on top just causes confusion).

### O.3 Add swap (protects the build on 2 cores / 12 GB)
Building 9 Maven images at once is the only step that can strain 12 GB. A swap file is the
simple safety net — add it **before** the first build:
```bash
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### O.4 Install Docker, then launch with the Oracle overlay
```bash
curl -fsSL https://get.docker.com | sudo sh          # installs arm64 Docker + compose plugin
```
Now do §1 (DNS A record → the instance's public IP) and §3 (`git clone` + fill `.env`,
including `FINSIGHT_DOMAIN`, `FINSIGHT_ACME_EMAIL`, `GF_SECURITY_ADMIN_PASSWORD`). Then launch
with the **third** overlay added — it pins Kafka's heap and Prometheus retention for the
smaller box:
```bash
COMPOSE_PARALLEL_LIMIT=1 docker compose \
  -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.oracle.yml \
  up -d --build
```
The images are multi-arch, so building **on the A1 box** produces arm64 images with no
Dockerfile change. `COMPOSE_PARALLEL_LIMIT=1` serializes the work so 2 cores aren't
oversubscribed; the first build still takes several minutes. From here, §4–§8 (verify, backups,
updates) apply unchanged — just keep all three `-f` flags on every `docker compose` command.

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
# JWT RS256 keypair — auth-service signs with the private key; every other service
# verifies with the public key only. Prints JWT_PRIVATE_KEY= and JWT_PUBLIC_KEY=
# (base64 DER, one line each) ready to paste into .env:
./scripts/gen-jwt-keys.sh                 # or: ./scripts/gen-jwt-keys.sh >> .env
openssl rand -base64 24 | tr -d '/+=\n'   # each *_DB_PASSWORD, MYSQL_ROOT_PASSWORD, GF_SECURITY_ADMIN_PASSWORD
```

Both key vars have **no default** — every service fails fast if `JWT_PRIVATE_KEY` /
`JWT_PUBLIC_KEY` is unset. Generate the pair **once** and keep it stable across restarts
(regenerating invalidates all existing tokens).

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
points): **JWKS endpoint + JWT key rotation** (signing is already RS256 asymmetric, but there is no
published JWKS or rotation flow), edge rate limiting, transactional outbox, managed/replicated MySQL,
distributed tracing, Prometheus alerting, and any multi-host / HA topology. For "production-grade",
that is Path B.

## Optional: publish images to a registry (GHCR)

Path A builds images on the VPS from source, which needs no registry. If you would rather pull
prebuilt images (faster VPS boots, reproducible releases), add a GitHub Actions job that builds
and pushes each `services/*` and `web/` image to `ghcr.io/<owner>/finsight-<name>`, then replace
the `build:` keys in a compose overlay with `image:` references. This is listed as an optional
enhancement in the roadmap, not a requirement for the demo.
