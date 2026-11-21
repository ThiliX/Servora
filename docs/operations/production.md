# Running the production profile

The whole system in containers, hardened as described in [ADR-021](../architecture/adr/0021-production-deployment-profile.md). This is the configuration the CI job "Production stack" builds, scans and tests on every pull request.

## Start

```bash
cp .env.example .env    # then replace every placeholder with your own secrets
docker compose -f docker-compose.yml -f compose.prod.yml up -d --build --wait
```

- Dashboard and API: <http://localhost:8000>
- Widget host: <http://localhost:8001>, which serves the loader and forwards the public widget API. The Widget page shows the embed snippet with this host's loader URL

Order of start-up: PostgreSQL, Redis and storage; the `migrations` container applies any new migrations and exits; the worker and AI service; the core API, once migrations succeeded; then the gateway and widget host.

Use a different project name (`-p servora-prod`) to keep the production profile's volumes apart from a development stack on the same machine.

## Settings specific to this profile

| Variable | Default | Meaning |
|---|---|---|
| `SERVORA_PUBLIC_BIND` | `127.0.0.1` | Address the two public ports bind to. Put a TLS-terminating proxy in front before binding beyond this machine |
| `SERVORA_WEB_PORT`, `SERVORA_WIDGET_PORT` | `8000`, `8001` | Published ports |
| `SERVORA_PROD_WEB_ORIGIN` | `http://localhost:8000` | Public origin of the dashboard and API, used in verification and invitation links |
| `SERVORA_PROD_WIDGET_ORIGIN` | `http://localhost:8001` | Public origin of the widget host: the loader URL in the embed snippet, and the only origin allowed to call the conversation endpoints |
| `SERVORA_PROD_MAIL_LOG_LINKS` | `false` | Writes verification and invitation links to the API log. For the CI browser tests only |

Everything else comes from `.env`, as in development.

## What is different from development

| | Development | Production profile |
|---|---|---|
| Core API, dashboard, widget | On the host (`mvnw`, `npm run dev`) | Containers |
| Migrations | Run by the API at start-up | One-shot container with the owner role; the API never gets the owner's password |
| Published ports | Every service, on 127.0.0.1 | Gateway (8000) and widget host (8001) only |
| Data network | Normal | Internal: no internet access. Only the AI service has a second network for the model provider |
| Containers | Defaults | Non-root, read-only root filesystem, no capabilities, no privilege escalation, memory and CPU limits |
| API documentation | Swagger UI at `/swagger-ui.html` | Disabled |
| Logs | Text lines with trace and span IDs | One JSON object per line, with the same fields |
| Client address for rate limits | The connection | Set by the gateway; trusted only from private addresses |

## Checks

```bash
COMPOSE="docker compose -p servora-prod -f docker-compose.yml -f compose.prod.yml"
$COMPOSE ps                                   # all healthy; migrations exited (0)
$COMPOSE ps --format '{{.Service}} {{.Ports}}' # only web and widget show "->"
$COMPOSE logs migrations                      # "Successfully applied" or "up to date"
```

Browser tests against this profile: `E2E_TARGET=prod npx playwright test` in `tests/e2e`, with the profile started with `SERVORA_LLM_PROVIDER=fake SERVORA_PROD_MAIL_LOG_LINKS=true`. See [tests/e2e/README.md](../../tests/e2e/README.md).

Observability: JSON logs with trace IDs are on in this profile. For Prometheus and Jaeger, add `-f compose.observability.yml -f compose.observability.prod.yml`; see [observability.md](observability.md). `infrastructure/ci/check-observability.sh` checks the running profile, as CI does.

Backups: [backup-and-restore.md](backup-and-restore.md).

## Not covered yet

- TLS, a domain name and a proxy in front of the two entry points.
- Email delivery.
- Running more than one API instance.
