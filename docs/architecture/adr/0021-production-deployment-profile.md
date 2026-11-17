# ADR-021: Production deployment profile

- **Status:** Accepted (decided in M7)
- **Date:** 2026-11-17

## Context

Until M7 only the infrastructure, worker and AI service ran in containers; the core API and dashboard ran on the host, and the API ran migrations at start-up, so the running API held the database owner's password. The master prompt asks for non-root containers, pinned tags, internal networks, no public database or Redis ports in production, separate development and production configuration, and image scanning.

## Decision

**One overlay.** `compose.prod.yml` is layered on `docker-compose.yml`. Development keeps using the base file alone, with loopback ports for running the API and dashboard on the host.

**Migrations apart from the API.** A one-shot `migrations` container runs `com.servora.Migrate` from the API image: Flyway with the owner role, the same migrations and placeholders as `spring.flyway`. The API starts only after it exits successfully, with Flyway disabled and only the runtime role's credentials. Running the migrations from the API image, rather than the Flyway CLI image, keeps one scanned image with the same Flyway library and driver; the CLI image carried 129 known vulnerabilities in its bundled packages.

**Three networks.** `backend` is internal: no route to the internet, used by PostgreSQL, Redis, storage, the worker, the AI service and the API. `egress` lets only the AI service reach the model provider. `edge` holds the published gateway, the widget host and the API's way in. No data service publishes a port.

**Two entry points.** The dashboard image (nginx) serves the app and proxies `/api` to the core API, so the dashboard and API share an origin; the widget image serves the loader and the iframe page on a second origin, and forwards only the public widget API (`/api/v1/public/`) to the core API, so a website's embed snippet needs nothing but the loader's URL and the iframe talks only to its own origin. The dashboard shows that URL, built from the configured widget origin. The gateway replaces `X-Forwarded-For` with the client's address, and the API, which trusts forwarded headers only from private addresses, sees the real client for rate limits. Both bind to `127.0.0.1` unless `SERVORA_PUBLIC_BIND` says otherwise; TLS belongs to a proxy in front of them.

**Hardened containers.** Application containers run as non-root users, with read-only root filesystems, a `tmpfs` for `/tmp`, all Linux capabilities dropped, no privilege escalation, and memory and CPU limits. Base images are pinned to tags at least two weeks old and upgraded with the distribution's security updates at build time. The Python images remove `pip`, which is not needed at run time and bundles its own outdated packages. The Swagger UI and OpenAPI document are disabled in this profile.

**Security headers on static content.** The dashboard is served with `default-src 'self'` and `frame-ancestors 'none'`. The widget page may be framed by any website, because the API enforces the allowed websites, and may connect only to its own origin.

**Scanning and testing in CI.** The "Production stack" job builds every image from the commit, fails on any fixable high or critical vulnerability (Trivy), starts the profile, checks that the API holds no owner password and that only the two entry points publish ports, and runs the browser suite against it.

## Consequences

- A deployment needs the migrations container to finish before the API starts; Compose orders this.
- A rebuilt base image can make the image scan fail until the image is rebuilt or a fix is published; the scan ignores vulnerabilities without a fix.
- The widget page cannot restrict who frames it to each workspace's websites, since one page serves every workspace.
- Mail delivery is still not built; `SERVORA_PROD_MAIL_LOG_LINKS` exists for the CI browser tests only.
