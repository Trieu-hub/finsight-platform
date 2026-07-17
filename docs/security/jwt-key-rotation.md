# JWT Signing Key Rotation Runbook

FinSight signs access tokens with **RS256**: `auth-service` holds the RSA **private** key and
is the only thing that can mint a token; the other seven components (api-gateway plus user,
transaction, budget, dashboard, notification and analytics services) verify with the
**public** key and cannot forge anything.

Which public key verifies a given token is decided by the token's `kid` header against the
JWK Set `auth-service` publishes at **`/.well-known/jwks.json`**. That indirection is what
makes rotation a routine, no-downtime operation:

- **Only `auth-service` restarts.** The seven validators discover the new key themselves.
- **Nobody is logged out.** The outgoing key keeps verifying until the tokens it signed have
  expired on their own.

> **Historical note.** This used to describe a shared symmetric `JWT_SECRET` and a
> coordinated cutover of every service at once, with a window of cross-service 401s. Both the
> shared secret and that window are gone: RS256 removed the secret, and the JWK Set removed
> the cutover.

## When to rotate

- **Routinely:** on a schedule (e.g. quarterly), and on operator offboarding.
- **Immediately:** on any suspected leak of `JWT_PRIVATE_KEY`. Whoever holds that key can
  mint a valid token for **any user, including an admin** — see [Emergency rotation](#emergency-rotation-the-key-leaked),
  which deliberately trades a forced logout for closing that hole now.

## How it works (read this before you run it)

Two facts drive the whole procedure:

1. An access token lives for **15 minutes** (`JWT_ACCESS_TOKEN_EXPIRATION`). At the moment
   the signing key changes, tokens signed by the old key are still in users' browsers and
   valid for up to that long.
2. Each key's `kid` is the **RFC 7638 thumbprint** of the key itself — a hash of the key
   material, not a name anyone assigns. So `auth-service` and every validator compute the
   same id for the same key independently. There is no id to bump, collide, or mistype.

Hence a rotation has an **overlap window**: for one access-token lifetime the JWK Set
advertises *two* keys — the new one (signing + verifying) and the outgoing one (verifying
only). Dropping the old key immediately would 401 every session opened in the last 15
minutes.

```
  start ──────────────► [ overlap: 2 keys published ] ──────────────► finish
  new key signs          old tokens still verify                      old key dropped
  auth-service restarts  validators learn the new key via JWKS        auth-service restarts
                         (no restart needed)
```

## Procedure

Run from the repo root. `scripts/rotate-jwt-key.sh` does the key generation and the `.env`
edits; it never touches a running container.

### 1. Start the rotation

```bash
./scripts/rotate-jwt-key.sh start --write   # writes .env, keeps a timestamped backup
docker compose up -d auth-service           # ONLY auth-service
```

This sets, in `.env`:

| Variable | Becomes |
|---|---|
| `JWT_PRIVATE_KEY` | the new private key (signs from now on) |
| `JWT_PUBLIC_KEY` | the new public key |
| `JWT_PREVIOUS_PUBLIC_KEYS` | the **outgoing** public key — verifies, never signs |

Omit `--write` to print the values and edit `.env` by hand instead.

### 2. Verify the overlap is live

```bash
# The JWK Set must now advertise TWO keys:
curl -s http://localhost:8081/.well-known/jwks.json | grep -o '"kid"' | wc -l   # -> 2
```

Then check both halves in the app:

- A **fresh login** works (the new key signs, and validators fetched it from the JWK Set).
- A session **opened before the rotation** still works — this is the overlap window doing its
  job, and the thing worth actually clicking. If it 401s, the old key is not being published;
  go to [Rollback](#rollback).

A validator picks the new key up within **5 minutes** (its JWK Set cache TTL), or instantly
the first time it sees the unfamiliar `kid`.

### 3. Finish, after the window closes

Wait out the access-token TTL — **15 minutes** by default — so that every token signed by the
old key has expired on its own:

```bash
./scripts/rotate-jwt-key.sh finish --write
docker compose up -d auth-service

curl -s http://localhost:8081/.well-known/jwks.json | grep -o '"kid"' | wc -l   # -> 1
```

The old key is now published nowhere and is refused everywhere.

### 4. Housekeeping

Delete the `.env.bak.*` files the script left once the rotation is confirmed — they contain
the **old private key**, which is exactly what an attacker would want if the reason for
rotating was a leak.

```bash
shred -u .env.bak.* 2>/dev/null || rm -f .env.bak.*
```

## Emergency rotation (the key leaked)

Skip the wait. Run `start`, then `finish` immediately:

```bash
./scripts/rotate-jwt-key.sh start --write  && docker compose up -d auth-service
./scripts/rotate-jwt-key.sh finish --write && docker compose up -d auth-service
```

Every session in flight is logged out (clients holding a refresh token recover on their own
via `POST /api/v1/auth/refresh` — refresh tokens are opaque Redis values, not signed, so they
survive). That is the correct trade: a leaked private key mints tokens for **any** user,
including admins, and no amount of user convenience is worth leaving that open for 15 minutes.

Then confirm the leaked key is gone from every environment before closing the incident.

## Rollback

The backup the script writes is a complete previous `.env`:

```bash
cp .env.bak.<timestamp> .env
docker compose up -d auth-service
```

Because the old key was still being published during the overlap window, rolling back inside
that window is safe — validators simply go on accepting it.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Every request 401s right after `start` | validators cannot reach the JWK Set, and `JWT_PUBLIC_KEY` in their environment is the *old* key from before their last restart | check `docker compose logs api-gateway \| grep -i jwks`; confirm `JWT_JWKS_URI` is set and `auth-service` is healthy |
| Only *some* requests 401 | one validator's JWK Set cache is stale | wait out the 5-minute TTL, or restart that service |
| `finish` breaks pre-existing sessions | run before the 15-minute window elapsed | expected; those clients refresh automatically |
| `finsight.jwks.refresh.failed` climbing | validators cannot fetch the JWK Set | `auth-service` unreachable — they are running on cached keys and a rotation **will not propagate** until this is fixed |

## Hard rules

- `JWT_PRIVATE_KEY` lives **only** in `.env` (local) or the platform secret store (deployed) —
  never in `docker-compose.yml`, `application.yml`, or any committed file. `.env` is gitignored.
- **Only `auth-service`** is ever given `JWT_PRIVATE_KEY`. A validator that had it could mint
  tokens, which is the entire weakness RS256 removed.
- `JWT_PREVIOUS_PUBLIC_KEYS` is empty at steady state. A key left there indefinitely is a key
  that can still sign valid-looking tokens forever — finish the rotation.
- The JWK Set contains public keys only. If `/.well-known/jwks.json` ever shows a `"d"`
  member, stop: that is a private key on the wire. (`JwksEndpointIntegrationTest` asserts it
  does not.)
