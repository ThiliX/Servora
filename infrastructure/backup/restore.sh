#!/usr/bin/env bash
# Restores a backup made by backup.sh into a stack whose volumes are new and empty.
#
# Usage: infrastructure/backup/restore.sh <backup directory, e.g. backups/servora-20261117T150000Z>
# Environment: COMPOSE and PROJECT, as for backup.sh.
#
# Steps: verify checksums; start PostgreSQL alone, which creates the runtime roles on the new volume;
# restore the database; restore the document storage volume; start everything. The migrations
# container then finds the schema at the latest version and changes nothing.
#
# It refuses to run if the database already contains Servora tables, so it cannot overwrite live data.
set -euo pipefail

source_dir="$(cd "${1:?usage: restore.sh <backup directory>}" && pwd)"
compose="${COMPOSE:-docker compose -f docker-compose.yml -f compose.prod.yml}"
project="${PROJECT:-servora}"

echo "Verifying checksums..."
(cd "$source_dir" && sha256sum --check SHA256SUMS)

echo "Starting PostgreSQL..."
$compose -p "$project" up -d --wait postgres

existing=$($compose -p "$project" exec -T postgres sh -c \
  'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -Atc "SELECT count(*) FROM information_schema.tables WHERE table_schema = '"'"'public'"'"' AND table_name = '"'"'tenants'"'"'"')
if [ "$existing" != "0" ]; then
  echo "The database already has Servora tables. Restore only into a new, empty volume." >&2
  exit 1
fi

echo "Restoring database..."
$compose -p "$project" exec -T postgres sh -c \
  'pg_restore --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --exit-on-error --single-transaction' \
  < "$source_dir/database.dump"

echo "Restoring document storage..."
# Creates the storage container and its volume without starting it.
$compose -p "$project" create storage
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "${project}_storage-data:/data" \
  -v "$source_dir:/backup:ro" \
  alpine:3.22.2 sh -c 'if [ -n "$(ls -A /data)" ]; then echo "Storage volume is not empty" >&2; exit 1; fi; tar -xzf /backup/storage.tar.gz -C /data'

echo "Starting the stack..."
$compose -p "$project" up -d --wait
echo "Restore complete."
