#!/usr/bin/env bash
# Backs up Servora's state: the PostgreSQL database and the uploaded documents in object storage.
# Redis holds only rate-limit counters and the worker queue, which are rebuilt, so it is not backed up.
#
# Usage: infrastructure/backup/backup.sh <backup directory>
# Environment:
#   COMPOSE   the compose command for the stack (default: docker compose -f docker-compose.yml -f compose.prod.yml)
#   PROJECT   the compose project name (default: servora)
#
# Writes <dir>/servora-<timestamp>/{database.dump,storage.tar.gz,SHA256SUMS}. The database dump is in
# PostgreSQL's custom format and includes the schema, data, Row-Level Security policies and grants.
# Database roles are not in it: they are created by infrastructure/database/init on a new volume.
# The files contain customer data. Store them encrypted, with restricted access.
set -euo pipefail

target_root="${1:?usage: backup.sh <backup directory>}"
compose="${COMPOSE:-docker compose -f docker-compose.yml -f compose.prod.yml}"
project="${PROJECT:-servora}"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$target_root/servora-$stamp"
mkdir -p "$target"
target="$(cd "$target" && pwd)"

echo "Backing up database..."
# --no-owner is not used: the owner role is the same on the restored cluster.
$compose -p "$project" exec -T postgres sh -c 'pg_dump --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --format=custom' \
  > "$target/database.dump"

echo "Backing up document storage..."
# Read-only mount of the storage volume; the storage service keeps running.
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "${project}_storage-data:/data:ro" \
  -v "$target:/backup" \
  alpine:3.22.2 tar -czf /backup/storage.tar.gz -C /data .

(cd "$target" && sha256sum database.dump storage.tar.gz > SHA256SUMS)
echo "Backup written to $target"
ls -l "$target"
