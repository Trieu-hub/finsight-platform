# ADR-0005 — RS256 asymmetric JWT signing

- Status: **Accepted / Frozen**
- Date: 2026-07-18
- Scope: the JWT **signing algorithm** and the key-trust model for the whole platform —
  `auth-service` (the sole issuer) and the seven token validators (api-gateway, user,
  transaction, budget, dashboard, notification, analytics).
- Supersedes: **ADR-0002 §1 (JWT Algorithm — HS512)** in full, including its
  `JWT_SECRET ≥ 512 bits` constraint. All other ADR-0002 sections — §1a (`kid`/JWK Set
  discovery), §2 (`iss`), §3 (`aud`), §4–§9 — remain in force unchanged.

This ADR **locks** the values below, each verified against the running source
(`auth-service` `JwtService`/`JwtKeyRegistry`/`JwtProperties`, `api-gateway`
`JwtAuthenticator`/`JwtProperties`, the shared `JwtKeyResolver`, `docker-compose.yml`).
**Changing any locked value requires a new ADR, not an edit to this file.**

Governing invariant (unchanged from ADR-0001/0002): the gateway validates but does **not**
replace downstream validation; every service keeps validating tokens locally; the token
contract stays additive so the gateway remains removable without service changes.

---

## Context — why this ADR exists

ADR-0002 §1 froze the algorithm as **HS512** with a single shared `JWT_SECRET`. Under that
model **every** validator had to hold the *signing* secret in order to verify, because an HMAC
key both signs and verifies. Two consequences followed:

1. **Blast radius.** The secret that mints tokens lived in all eight components. A read of any
   one service's environment (a leaked `.env`, a compromised container, a logged variable)
   yielded the power to forge tokens for every user and role. Verification capability and
   issuance capability were the same key — they could not be separated.
2. **Rotation was a lockstep cutover.** Replacing the secret meant restarting all eight
   components in near-unison; any straggler either rejected new tokens or kept honouring the old
   secret. ADR-0002 §1a later solved the *key-identity* half of this with `kid` + JWK Set
   discovery, but that machinery only pays off with an **asymmetric** key, where the thing being
   discovered (a public key) is not the thing that must stay secret.

The platform migrated HS512 → **RS256** to fix both, but **no ADR recorded the change** — ADR-0002
§1a explicitly flagged §1 as stale and the missing ADR as a documentation gap. This ADR closes
that gap and makes the current, running behaviour the governed contract.

## 1. Decision — **RS256 (RSASSA-PKCS1-v1_5 with SHA-256)**

`auth-service` signs every access token with an **RSA private key** using **RS256**
(`JwtService`: `REQUIRED_ALG = "RS256"`, `.signWith(keys.signingKey(), Jwts.SIG.RS256)`;
`JwtKeyRegistry.signingKey` is a `java.security.PrivateKey`, and the registry rejects a
non-RSA key at startup). Validators verify with the corresponding **public** key only.

**Locked:** the emitted `alg` is **RS256**. The signing key is RSA. HS512 and the shared-HMAC
model are retired.

## 2. Key trust — private key held by auth-service alone

- **`auth-service` is the only holder of the private key** (`JWT_PRIVATE_KEY`). It is the only
  component that can mint a token. The private key must never leave the service — no endpoint,
  log line, or response may emit it (`/.well-known/jwks.json` publishes public material only).
- **Every validator holds the public key only** (`JWT_PUBLIC_KEY`, and/or a key fetched by
  `kid` from the JWK Set per ADR-0002 §1a). A validator can *check* a token but **cannot forge
  one**. This is the property HS512 could not provide.

**Locked:** issuance capability (private key) is isolated to auth-service; verification
capability (public key) is all any other service ever receives.

## 3. Algorithm pinning — no confusion downgrade

Each validator pins RS256 explicitly rather than trusting the token's self-declared `alg`:

- `parseSignedClaims()` requires a **signed** JWS, so an unsecured **`none`** token is rejected
  before any key is consulted.
- The verification key is resolved by `kid` and is an **RSA public key**, so a token forged with
  a symmetric (`HS*`) algorithm — the classic *RS→HS key-confusion* attack, where the public key
  is fed to an HMAC verifier — fails on key-type mismatch.
- An **explicit `alg == RS256` check** (`JwtAuthenticator` line ~84) then rejects the sibling
  RSA algorithms **RS384/RS512**, which the same public key would otherwise verify.

**Locked:** validators MUST reject any `alg` other than `RS256`, including `none`, any `HS*`,
and `RS384`/`RS512`.

## 4. Consequences

- **Security.** Compromise of any validator no longer yields token-forging power — the worst it
  leaks is a public key. Forgery now requires the single private key on auth-service.
- **Rotation.** RS256 is what makes ADR-0002 §1a's zero-downtime rotation real: the JWK Set
  advertises public keys, discovered by `kid`, and only auth-service restarts to rotate. The
  private key is never distributed, so there is nothing to roll out across eight components.
- **Operational / env.** `JWT_SECRET` is replaced by **`JWT_PRIVATE_KEY`** (auth-service only)
  and **`JWT_PUBLIC_KEY`** (all validators), with **`JWT_PREVIOUS_PUBLIC_KEYS`** holding the
  outgoing public key during a rotation window (empty at steady state). Keys are generated by
  `scripts/gen-jwt-keys.sh`; rotation follows `docs/security/jwt-key-rotation.md`.
- **The `JWT_SECRET ≥ 512 bits` constraint is void.** It protected HS512 key strength and has no
  analogue here; RSA key strength is a property of the generated keypair, and a malformed or
  non-RSA key fails fast in `JwtKeyRegistry`/`JwtKeyResolver` at startup rather than at first
  validation.
- **Claim set unchanged.** RS256 changes only *how* a token is signed. The frozen claim set
  (`sub`/`userId`/`email`/`role`/`iss`/`aud`) and the `iss`/`aud` enforcement of ADR-0002 §2–§3
  are untouched, so this migration was additive and required no downstream contract change.

## 5. Relationship to ADR-0002

- **Supersedes §1** (HS512 pin and the `JWT_SECRET ≥ 512 bits` constraint) entirely. The stale
  §1 and its §1a warning box are now resolved by this ADR; §1 should be read as historical.
- **Builds on §1a.** `kid`/JWK Set discovery, the two-key rotation window, and the fail-static
  posture described there assume an asymmetric key — this ADR pins that assumption.
- **Leaves §2–§9 in force.** `iss`, `aud`, public routes, error codes (including §5a
  revocation), identity headers, and correlation headers are unaffected.

---

> **Verification provenance:** `auth-service/.../security/jwt/JwtService.java`
> (`REQUIRED_ALG = "RS256"`, `signWith(..., Jwts.SIG.RS256)`), `JwtKeyRegistry.java`
> (RSA `PrivateKey` signing key, non-RSA rejected at startup), `JwtProperties.java`
> (`privateKey`); `api-gateway/.../security/JwtAuthenticator.java` (`parseSignedClaims` +
> `keyLocator` + explicit `RS256` pin), `api-gateway/.../config/JwtProperties.java`
> (`publicKey`); the shared `JwtKeyResolver` (fetch-by-`kid`, fail-static to `JWT_PUBLIC_KEY`);
> `docker-compose.yml` (`JWT_PRIVATE_KEY` on auth-service only; `JWT_PUBLIC_KEY` /
> `JWT_PREVIOUS_PUBLIC_KEYS` on the validators). Algorithm confirmed empirically from an issued
> token header `{"alg":"RS256"}` and the live JWK Set at `/.well-known/jwks.json`.
