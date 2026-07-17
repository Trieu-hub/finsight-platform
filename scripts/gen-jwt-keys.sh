
#!/usr/bin/env bash
# Generates an RSA-2048 keypair for FinSight's RS256 JWTs and prints the two env values
# (base64-encoded DER, single line each) ready to paste into .env:
#
#   JWT_PRIVATE_KEY=  -> auth-service only (signs access tokens)
#   JWT_PUBLIC_KEY=   -> every service (verifies; cannot forge)
#
# Usage:  ./scripts/gen-jwt-keys.sh            # print to stdout
#         ./scripts/gen-jwt-keys.sh >> .env    # append to your .env (then edit as needed)
#
# Requires openssl. The keys are never written to disk by this script.
set -euo pipefail

priv_pem="$(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null)"
pub_pem="$(printf '%s' "$priv_pem" | openssl pkey -pubout 2>/dev/null)"

# Strip PEM armor + newlines -> the bare base64 DER body the apps parse.
priv_b64="$(printf '%s' "$priv_pem" | grep -v -- '-----' | tr -d '\n')"
pub_b64="$(printf '%s' "$pub_pem"  | grep -v -- '-----' | tr -d '\n')"

printf 'JWT_PRIVATE_KEY=%s\n' "$priv_b64"
printf 'JWT_PUBLIC_KEY=%s\n'  "$pub_b64"
