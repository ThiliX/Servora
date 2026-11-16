#!/bin/sh
# Creates the application's runtime database role.
#
# PostgreSQL runs this once, when the data volume is first initialised. The role is created here
# rather than in a migration so its password never appears in versioned SQL. It is deliberately
# unprivileged: no superuser, no BYPASSRLS, and it owns nothing. Flyway migrations, run by the
# owner role, grant it only the table privileges it needs.
set -eu

: "${SERVORA_POSTGRES_APP_USER:?SERVORA_POSTGRES_APP_USER is required}"
: "${SERVORA_POSTGRES_APP_PASSWORD:?SERVORA_POSTGRES_APP_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  -v app_user="$SERVORA_POSTGRES_APP_USER" \
  -v app_password="$SERVORA_POSTGRES_APP_PASSWORD" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS',
    :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user') \gexec
SQL
