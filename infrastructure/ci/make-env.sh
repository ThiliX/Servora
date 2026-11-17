#!/usr/bin/env bash
# Writes a .env for a throwaway CI stack: .env.example with every password and key replaced by a
# random value. Never use it for a stack that holds real data; generate and keep your own secrets.
set -euo pipefail

cp .env.example .env
set_value() {
  # Base64 never contains "|", so it is a safe sed delimiter here.
  sed -i "s|^$1=.*|$1=$2|" .env
}
for name in SERVORA_POSTGRES_OWNER_PASSWORD SERVORA_POSTGRES_APP_PASSWORD SERVORA_POSTGRES_WORKER_PASSWORD \
  SERVORA_POSTGRES_AI_PASSWORD SERVORA_STORAGE_ACCESS_KEY SERVORA_STORAGE_SECRET_KEY; do
  set_value "$name" "ci-$(openssl rand -hex 16)"
done
for name in SERVORA_JWT_SECRET SERVORA_INTERNAL_TOKEN_SECRET SERVORA_WIDGET_TOKEN_SECRET; do
  set_value "$name" "$(openssl rand -base64 48 | tr -d '\n')"
done
if grep -q "replace-with" .env; then
  echo "A placeholder was left in .env:" >&2
  grep "replace-with" .env | sed 's/=.*/=.../' >&2
  exit 1
fi
