#!/usr/bin/env bash
# Run docker compose (base + prod overlay) with secrets decrypted from secrets.env
# into the process environment via SOPS -- so no plaintext .env sits on disk.
# Requires the age private key at /root/.config/sops/age/keys.txt (chmod 600).
#   Usage: scripts/prod-compose.sh up -d --build   |   scripts/prod-compose.sh ps
set -euo pipefail
cd "$(dirname "$0")/.."
exec sops exec-env secrets.env "docker compose -f docker-compose.yml -f docker-compose.prod.yml $*"
