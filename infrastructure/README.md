# Infrastructure

- `docker/` — Dockerfiles and container support files, added with each service.
- `database/init/` — creates the unprivileged runtime database roles (application and document worker) when the PostgreSQL volume is first initialised. Backup and restore scripts arrive in M7.
- `monitoring/` — metrics and tracing configuration (M7).

The local stack is defined in [docker-compose.yml](../docker-compose.yml).
