# Deploying FinSight — Path A (single VPS + HTTPS)

This is the **"demo on the internet"** path from
[`project-status.md` §10](../project-status.md): one VPS running the existing Docker Compose
stack, fronted by **Caddy** for automatic HTTPS. It turns the local stack into a public
`https://…` demo with the least operational cost. It is **not** a production-hardening guide
(no HA, no managed DB) — those stay future-scoped. JWT signing is **RS256 asymmetric**
(auth-service holds the private key; every other service verifies with the public key only), so
this guide has you generate an RSA keypair rather than a shared secret. That keypair can be
**rotated without downtime** once deployed — see
[security/jwt-key-rotation.md](security/jwt-key-rotation.md).

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
(regenerating by hand invalidates all existing tokens). To *replace* the key later, do not
hand-edit these — use `./scripts/rotate-jwt-key.sh`, which keeps the outgoing key verifying
until the tokens it signed expire, so the change costs no downtime and no forced logout
([security/jwt-key-rotation.md](security/jwt-key-rotation.md)).

`JWT_PREVIOUS_PUBLIC_KEYS` is optional and empty except during a rotation; leaving it out of
`.env` entirely is fine.

Required for prod (in addition to the dev vars):

```dotenv
FINSIGHT_DOMAIN=finsight.example.com
FINSIGHT_ACME_EMAIL=you@example.com
GF_SECURITY_ADMIN_PASSWORD=<strong-password>
```

> **Never commit `.env`.** It is gitignored. Optionally enable the AI features
> (`FINSIGHT_NARRATOR_AI_ENABLED` / `FINSIGHT_SUMMARIZER_AI_ENABLED` + `LLM_API_KEY`).

## 3.1 Encrypt the secrets at rest with SOPS + age (recommended)

`.env` at `chmod 600` keeps the secrets off the network but still leaves them in **plaintext
on disk**. The live deploy encrypts them at rest with [SOPS](https://github.com/getsops/sops)
+ [age](https://github.com/FiloSottile/age), so the only plaintext secret on the box is a
single age key. Because the base compose interpolates every secret as `${VAR}` (there is no
`env_file:`), SOPS injects them straight into the process environment for the life of one
command — **zero application change**.

```bash
# one-time: install the tools + generate an age keypair
apt-get install -y age
curl -sSL -o /usr/local/bin/sops \
  https://github.com/getsops/sops/releases/download/v3.9.4/sops-v3.9.4.linux.amd64
chmod +x /usr/local/bin/sops
mkdir -p /root/.config/sops/age
age-keygen -o /root/.config/sops/age/keys.txt      # prints the age1… public recipient
chmod 600 /root/.config/sops/age/keys.txt
```

Put that recipient in `.sops.yaml` (already committed — swap in your own key), then encrypt
the filled-in `.env` into `secrets.env` and remove the plaintext:

```bash
cp .env secrets.env
sops -e -i secrets.env                 # values become ENC[…]; the KEY names stay readable
sops -d secrets.env | diff - .env      # sanity round-trip (SOPS drops blank/comment lines)
rm .env                                # ONLY after you have an off-box backup (see below)
```

> **Trailing-whitespace gotcha (this bit us).** Compose **trims** trailing whitespace from
> `.env`-*file* values but **not** from shell-env values — which is exactly how SOPS feeds
> them in. A stray trailing space then makes the SOPS path and the old `.env` path disagree,
> and DB auth breaks on the next deploy. Strip it before encrypting
> (`sed -E 's/[[:space:]]+$//' .env > .env.clean`) and prove the rendered config is identical:
> `diff <(scripts/prod-compose.sh config) <(docker compose -f docker-compose.yml -f docker-compose.prod.yml config)`.

From here on, run every compose command through the wrapper — it decrypts `secrets.env` into
the environment only for that command:

```bash
scripts/prod-compose.sh up -d --build          # replaces the raw `docker compose … up` below
scripts/prod-compose.sh ps
```

**Back up two things off-box** (e.g. next to the DB dumps in §7.2): `secrets.env` **and**
`/root/.config/sops/age/keys.txt`. Lose the age key and the encrypted secrets are gone for
good. Per repo policy `secrets.env` and the age key are **gitignored** (kept off GitHub);
only `.sops.yaml` and `scripts/prod-compose.sh` are committed, so the tooling is reproducible
while the secret payload never leaves the box.

## 4. Launch

```bash
# with SOPS (§3.1): scripts/prod-compose.sh up -d --build
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

## 7. Backups & host hardening

### 7.1 Database backups (nightly, automated)

The live deploy runs `/root/backup-finsight.sh` via cron (`0 3 * * *`): it dumps **all** databases
from the `finsight-mysql` container, gzips into `/root/backups/`, keeps the newest 7, and aborts if
the dump is suspiciously small. The version-controlled source is **`scripts/backup-finsight.sh`** —
deploy it with `install -m 700 scripts/backup-finsight.sh /root/backup-finsight.sh` (or just copy
it) so the box runs the reviewed script, not a hand-pasted copy.

Install the cron entry (idempotent):

```bash
( crontab -l 2>/dev/null | grep -v backup-finsight.sh; \
  echo '0 3 * * * /root/backup-finsight.sh >> /root/backups/backup.log 2>&1' ) | crontab -
```

The script is configurable by env (`BACKUP_DIR`, `RETAIN`, `MYSQL_CONTAINER`, and `BACKUP_REMOTE`
for the off-box copy below); with none set it behaves exactly as the original local-only version.

**Restore** a dump:

```bash
gunzip -c /root/backups/finsight-YYYY-MM-DD_HHMM.sql.gz | \
  docker exec -i finsight-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
```

### 7.2 Off-box copies (automated)

Backups on the same VPS are lost if the VPS is, so `scripts/backup-finsight.sh` can push each dump
off-box in the same cron run. It uses [rclone](https://rclone.org) so the destination is your
choice — Backblaze B2, S3, Google Drive, an SFTP host, etc. — configured once and never hardcoded.

One-time setup on the VPS:

```bash
curl https://rclone.org/install.sh | sudo bash   # or: apt-get install -y rclone
rclone config                                     # create a remote, e.g. name it "offsite"
```

Then point the cron entry at the remote (bucket/folder path after the remote name):

```bash
( crontab -l 2>/dev/null | grep -v backup-finsight.sh; \
  echo '0 3 * * * BACKUP_REMOTE=offsite:my-bucket/finsight /root/backup-finsight.sh >> /root/backups/backup.log 2>&1' ) | crontab -
```

Each run uploads the new gzip and prunes the remote to the same `RETAIN` newest dumps as the local
copy. With `BACKUP_REMOTE` unset the off-box step is skipped (local-only, as before). Verify:

```bash
rclone ls offsite:my-bucket/finsight            # the rotations should appear after the next run
```

**Pull-based alternative** (no cloud account): from any machine with SSH access, copy the newest
rotation down on a schedule (cron / Windows Task Scheduler):

```bash
scp <user>@<host>:'/root/backups/*.sql.gz' /path/to/local/backups/
```

### 7.3 SSH, firewall & fail2ban

- **SSH is key-only** — password + keyboard-interactive auth disabled, root logs in by key only.
  Enforced by `/etc/ssh/sshd_config.d/99-hardening.conf`:
  ```
  PasswordAuthentication no
  KbdInteractiveAuthentication no
  PermitRootLogin prohibit-password
  ```
  (The provider's web console still offers password login as an out-of-band recovery path.)
- **Firewall (ufw)** — default-deny inbound, only 22/80/443 open:
  ```bash
  ufw default deny incoming && ufw default allow outgoing
  ufw allow 22/tcp && ufw allow 80/tcp && ufw allow 443/tcp
  ufw --force enable
  ```
  Note: Docker publishes 80/443 through its own iptables chain, bypassing ufw — fine here, since
  those are the only published ports and are meant to be public; everything else is un-published
  (internal Docker network). ufw guards host-level ports such as SSH.
- **fail2ban** — bans IPs that hammer SSH. `/etc/fail2ban/jail.local`:
  ```ini
  [sshd]
  enabled  = true
  backend  = systemd
  maxretry = 5
  findtime = 10m
  bantime  = 1h
  ```
  ```bash
  apt-get install -y fail2ban && systemctl enable --now fail2ban
  fail2ban-client status sshd          # list banned IPs
  fail2ban-client set sshd unbanip <IP>
  ```

### 7.4 Edge rate limiting

Auth endpoints are rate-limited at Caddy (custom build with the `caddy-ratelimit` plugin — see
`docker/caddy/Dockerfile`): `/api/v1/auth/register` + `/login` capped at 10 req/min/IP (keyed on
`CF-Connecting-IP`) → HTTP 429. Cloudflare **Bot Fight Mode** + a rate-limiting rule add a second
edge layer. **Never run load tests against production** — a load test once created ~84k junk
accounts here.

## 8. Updating

```bash
git pull
scripts/prod-compose.sh up -d --build      # or, without SOPS: docker compose -f … -f … up -d --build
```

Flyway applies any new migrations on service startup; the named volumes persist data. On an
8 GB box, prefix the build with `COMPOSE_PARALLEL_LIMIT=1` (and split `build` from `up -d`) so
the 9 JVM image builds don't oversubscribe RAM while the old containers keep serving.

---

## What this Path A deploy does and does NOT cover

**Covered:** public HTTPS URL, single-origin reverse proxy, only-Caddy-exposed network, Grafana
hardened, automated nightly backup **with optional off-box copy** (§7.2), **opt-in distributed
tracing** (OTLP → Tempo, via `--profile monitoring`), **SSH key-only**, **ufw firewall**,
**fail2ban**, **edge rate limiting** (Caddy `caddy-ratelimit` + Cloudflare Bot Fight Mode),
one-command update.

**Secrets at rest** are encrypted with SOPS + age (§3.1) — the plaintext `.env` is gone; only a
single age key stays in the clear. A **managed secrets store** with runtime injection (Vault /
Infisical) is still **deliberately out of scope** (that is Path B), as are managed/replicated
MySQL and any multi-host / HA topology (see `project-status.md` §6 / §10 — future work /
interview talking points).

## Optional: publish images to a registry (GHCR)

Path A builds images on the VPS from source, which needs no registry. If you would rather pull
prebuilt images (faster VPS boots, reproducible releases), add a GitHub Actions job that builds
and pushes each `services/*` and `web/` image to `ghcr.io/<owner>/finsight-<name>`, then replace
the `build:` keys in a compose overlay with `image:` references. This is listed as an optional
enhancement in the roadmap, not a requirement for the demo.
