#!/bin/sh
# Creates the unprivileged runtime database roles.
#
# PostgreSQL runs this once, when the data volume is first initialised. The roles are created here
# rather than in a migration so their passwords never appear in versioned SQL. Neither role is a
# superuser, can bypass Row-Level Security, or owns anything. Flyway migrations, run by the owner
# role, grant each one only the table privileges it needs:
#   - the application role serves core API requests
#   - the worker role lets the document worker read documents and write chunks, and nothing else
#   - the AI role lets the AI service read chunks for retrieval; it cannot write anything
set -eu

: "${SERVORA_POSTGRES_APP_USER:?SERVORA_POSTGRES_APP_USER is required}"
: "${SERVORA_POSTGRES_APP_PASSWORD:?SERVORA_POSTGRES_APP_PASSWORD is required}"
: "${SERVORA_POSTGRES_WORKER_USER:?SERVORA_POSTGRES_WORKER_USER is required}"
: "${SERVORA_POSTGRES_WORKER_PASSWORD:?SERVORA_POSTGRES_WORKER_PASSWORD is required}"
: "${SERVORA_POSTGRES_AI_USER:?SERVORA_POSTGRES_AI_USER is required}"
: "${SERVORA_POSTGRES_AI_PASSWORD:?SERVORA_POSTGRES_AI_PASSWORD is required}"

create_role() {
  psql -v ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    -v role_name="$1" \
    -v role_password="$2" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS',
    :'role_name', :'role_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'role_name') \gexec
SQL
}

create_role "$SERVORA_POSTGRES_APP_USER" "$SERVORA_POSTGRES_APP_PASSWORD"
create_role "$SERVORA_POSTGRES_WORKER_USER" "$SERVORA_POSTGRES_WORKER_PASSWORD"
create_role "$SERVORA_POSTGRES_AI_USER" "$SERVORA_POSTGRES_AI_PASSWORD"
