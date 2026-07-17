#!/usr/bin/env bash
# Rotates FinSight's RS256 JWT signing key with no downtime and no forced logout.
#
# Why this is two steps
# --------------------
# At the instant the signing key changes, tokens signed by the OLD key are still in users'
# browsers, valid for up to the access-token lifetime (15 min). Serving only the new key
# would reject all of them at once — a forced logout for every active user. So the old
# public key stays published, verifying but not signing, until those tokens have expired.
#
#   start   -> new key signs; old key still verifies (published in the JWK Set)
#             ... wait out the access-token TTL (15 minutes) ...
#   finish  -> old key dropped; anything it signed is now refused
#
# Only auth-service restarts. The other seven services discover the new key through
# /.well-known/jwks.json on their own — that is the entire point of publishing a JWK Set.
#
# Usage:
#   ./scripts/rotate-jwt-key.sh start            # print the new .env values
#   ./scripts/rotate-jwt-key.sh start  --write   # apply them to .env (backup taken)
#   ./scripts/rotate-jwt-key.sh finish --write    # end the overlap window
#
# Requires openssl. Run from the repo root. See docs/runbook.md for the full procedure.
set -euo pipefail

ENV_FILE="${ENV_FILE:-.env}"
MODE="${1:-}"
WRITE="${2:-}"

die() { printf 'error: %s\n' "$1" >&2; exit 1; }

case "$MODE" in
  start|finish) ;;
  *) die "usage: $0 {start|finish} [--write]" ;;
esac

[ -f "$ENV_FILE" ] || die "$ENV_FILE not found (run from the repo root, or set ENV_FILE=)"

# Read a bare KEY=value from the env file without sourcing it: sourcing would execute
# whatever else is in there, and these values are long base64 blobs, not shell.
read_var() {
  sed -n "s/^$1=//p" "$ENV_FILE" | head -1
}

current_public="$(read_var JWT_PUBLIC_KEY)"
[ -n "$current_public" ] || die "JWT_PUBLIC_KEY is empty in $ENV_FILE — nothing to rotate"

# Rewrites KEY=... in place, or appends it if absent. Uses perl with a NUL-safe literal
# replacement: base64 contains / and + which would be mangled by a naive sed s|| .
set_var() {
  local key="$1" value="$2"
  if grep -q "^$key=" "$ENV_FILE"; then
    KEY="$key" VALUE="$value" perl -i -pe '
      BEGIN { $k = $ENV{KEY}; $v = $ENV{VALUE}; }
      $_ = "$k=$v\n" if /^\Q$k\E=/;
    ' "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

if [ "$MODE" = finish ]; then
  # The overlap window is over: stop publishing the outgoing key. Any token it signed is
  # refused from here on — including one forged with it, which is the reason to rotate.
  if [ "$WRITE" = "--write" ]; then
    cp "$ENV_FILE" "$ENV_FILE.bak.$(date +%Y%m%d%H%M%S)"
    set_var JWT_PREVIOUS_PUBLIC_KEYS ""
    echo "Cleared JWT_PREVIOUS_PUBLIC_KEYS in $ENV_FILE (backup taken)."
  else
    echo "# Set this in $ENV_FILE, then restart auth-service:"
    echo "JWT_PREVIOUS_PUBLIC_KEYS="
  fi
  echo
  echo "Next: docker compose up -d auth-service"
  echo "Verify: the JWK Set should now list exactly ONE key:"
  echo "  curl -s http://localhost:8081/.well-known/jwks.json | grep -o '\"kid\"' | wc -l"
  exit 0
fi

# --- start ---------------------------------------------------------------------------
priv_pem="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null)"
pub_pem="$(printf '%s' "$priv_pem" | openssl pkey -pubout 2>/dev/null)"

# Strip PEM armor + newlines -> the bare base64 DER body the apps parse.
new_priv="$(printf '%s' "$priv_pem" | grep -v -- '-----' | tr -d '\n')"
new_pub="$(printf '%s' "$pub_pem"  | grep -v -- '-----' | tr -d '\n')"

if [ "$new_pub" = "$current_public" ]; then
  die "generated key matches the current one — refusing (this should be impossible)"
fi

if [ "$WRITE" = "--write" ]; then
  backup="$ENV_FILE.bak.$(date +%Y%m%d%H%M%S)"
  cp "$ENV_FILE" "$backup"
  set_var JWT_PRIVATE_KEY "$new_priv"
  set_var JWT_PUBLIC_KEY "$new_pub"
  # The key being replaced keeps verifying until the window closes.
  set_var JWT_PREVIOUS_PUBLIC_KEYS "$current_public"
  echo "Rotated $ENV_FILE (backup: $backup)."
else
  echo "# Replace these three lines in $ENV_FILE:"
  printf 'JWT_PRIVATE_KEY=%s\n' "$new_priv"
  printf 'JWT_PUBLIC_KEY=%s\n' "$new_pub"
  printf 'JWT_PREVIOUS_PUBLIC_KEYS=%s\n' "$current_public"
fi

cat <<'EOF'

Next:
  1. docker compose up -d auth-service        # ONLY auth-service; the rest need no restart
  2. Verify the JWK Set now advertises TWO keys (new + outgoing):
       curl -s http://localhost:8081/.well-known/jwks.json | grep -o '"kid"' | wc -l
  3. Log in and confirm the app works. Sessions opened before the rotation must ALSO
     still work — that is the overlap window doing its job.
  4. Wait out the access-token TTL (15 minutes by default), then:
       ./scripts/rotate-jwt-key.sh finish --write && docker compose up -d auth-service

If the key was rotated because it LEAKED, skip the wait and run `finish` immediately:
a forced logout is the correct trade when the old key can mint tokens.
EOF
