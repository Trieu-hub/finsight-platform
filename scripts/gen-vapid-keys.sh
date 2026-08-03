#!/usr/bin/env bash
# Generates the VAPID (RFC 8292) P-256 keypair that notification-service uses to sign web pushes,
# and prints the two env values ready to paste into .env:
#
#   PUSH_VAPID_PRIVATE_KEY=  -> notification-service only (signs the push Authorization header)
#   PUSH_VAPID_PUBLIC_KEY=   -> also handed to the browser, which pins its subscription to it
#
# Usage:  ./scripts/gen-vapid-keys.sh            # print to stdout
#         ./scripts/gen-vapid-keys.sh >> .env    # append to your .env
#
# Requires openssl. The keys are never written to disk by this script.
#
# IMPORTANT: the public key is baked into every subscription a browser creates. Changing the pair
# does not rotate cleanly — existing subscriptions keep the OLD key and their pushes start failing
# with 403, so every browser has to re-subscribe. Generate once and keep it.
set -euo pipefail

command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

openssl ecparam -name prime256v1 -genkey -noout -out "$tmp/vapid.pem" 2>/dev/null

# base64url, unpadded — what RFC 8292 puts in the `k=` parameter and what the Web Push API
# expects from `applicationServerKey`.
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# The private key is the raw 32-byte scalar, not a PKCS#8 blob. Read it out of the SEC1 DER
# rather than the text dump: for prime256v1 the DER always starts
#   30 77  02 01 01  04 20  <32 bytes>
# (SEQUENCE, INTEGER 1, OCTET STRING of length 0x20), so the scalar is bytes 8..39 — exact, and
# with none of the off-by-one that parsing the `priv:` hex dump invites.
private="$(openssl ec -in "$tmp/vapid.pem" -outform DER 2>/dev/null \
  | tail -c +8 | head -c 32 | b64url)"

# The public key is the 65-byte uncompressed point (0x04 || X || Y).
public="$(openssl ec -in "$tmp/vapid.pem" -pubout -outform DER 2>/dev/null \
  | tail -c 65 | b64url)"

echo "PUSH_VAPID_PUBLIC_KEY=${public}"
echo "PUSH_VAPID_PRIVATE_KEY=${private}"
