# Infrastructure

- `docker/` — Dockerfiles and container support files, added with each service.
- `database/init/` — creates the unprivileged runtime database roles (application and document worker) when the PostgreSQL volume is first initialised.
- `monitoring/` — Prometheus scrape configurations (development and production profile) and alert rules, used by `compose.observability.yml` ([observability.md](../docs/operations/observability.md)).
- `backup/` — backup and restore scripts ([backup-and-restore.md](../docs/operations/backup-and-restore.md)).
- `ci/` — scripts the CI production job runs: random secrets for a test environment, and the observability checks.

The local stack is defined in [docker-compose.yml](../docker-compose.yml).
