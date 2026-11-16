# Servora

Servora is a multi-tenant AI customer-support platform. Each business gets an isolated workspace with its own assistant, knowledge base, embeddable chat widget, conversations, tickets and team. The assistant answers from that business's approved documents, cites its sources, and hands off to a human agent when the evidence is not good enough.

## Status

**Milestone M1 (identity and tenant foundation) is implemented.** You can register, verify an email address, sign in, create workspaces, invite members with roles, and switch between workspaces. Tenant isolation is enforced by the application and by PostgreSQL Row-Level Security.

Not built yet: the AI assistant, knowledge ingestion, the chat widget, conversations, tickets and analytics. See [docs/milestones.md](docs/milestones.md).

## Architecture

```mermaid
flowchart LR
    Dashboard[React dashboard] --> Core[Spring Boot core API]
    Widget[Iframe chat widget] --> Core
    Core --> AI[Python AI service]
    Core --> PG[(PostgreSQL + pgvector)]
    Core --> Redis[(Redis)]
    Core --> Store[(Private object storage)]
    AI --> PG
    AI --> LLM[LLM provider]
    Redis --> Worker[Document worker]
    Worker --> PG
    Worker --> Store
```

Today only the dashboard, the core API and PostgreSQL are in use. Redis starts with the stack but nothing uses it until M2.

- **Core API** is the system of record for identity, tenancy, authorization, conversations, tickets, usage and audit.
- **AI service** will handle retrieval, prompt assembly, generation and evaluation. It holds no authority over permissions or ticket state.
- **Document worker** will extract, chunk, embed and index uploaded documents asynchronously.
- **Tenant isolation** uses a shared schema with `tenant_id`, tenant-aware foreign keys and PostgreSQL Row-Level Security.

More detail: [system overview](docs/architecture/system-overview.md), [decision records](docs/architecture/adr/README.md), [version matrix](docs/architecture/version-matrix.md).

## Prerequisites

| Tool | Version |
|---|---|
| Docker Engine and Compose | 29.x and v5.x |
| Java (JDK) | 25 |
| Node.js | 24.x LTS |

Maven is provided by the Maven Wrapper in `services/core-api`. Python is not needed yet.

## Local setup

### 1. Configure

```bash
cp .env.example .env
```

Edit `.env`:

- Set `SERVORA_POSTGRES_OWNER_PASSWORD` and `SERVORA_POSTGRES_APP_PASSWORD` to two different local passwords.
- Set `SERVORA_JWT_SECRET` to the output of `openssl rand -base64 48`.

`.env` is git-ignored. Docker Compose and the core API both read it.

### 2. Start the database

```bash
docker compose up -d --wait
```

The first start creates the unprivileged runtime role `servora_app`. If you started the stack before M1, that role does not exist in your old volume; recreate it with `docker compose down -v` (this deletes local data) and start again.

### 3. Start the core API

```bash
cd services/core-api && ./mvnw spring-boot:run
```

On Windows PowerShell use `.\mvnw.cmd spring-boot:run`. The API applies database migrations on startup and listens on <http://localhost:8080>. Health: <http://localhost:8080/actuator/health>. API reference: <http://localhost:8080/swagger-ui.html>.

### 4. Start the dashboard

```bash
cd apps/web && npm install && npm run dev
```

Open <http://localhost:5173>. The dev server proxies `/api` to the core API, so the browser sees one origin.

### 5. Verification and invitation emails

No email provider is connected yet. With `SERVORA_MAIL_LOG_LINKS=true` (the template default, for local development only) the core API writes each verification and invitation link to its log:

```text
DEV MAIL verification link: http://localhost:5173/verify-email#token=...
```

Open the link in the browser to continue. These links contain secret tokens, so never enable this setting outside local development.

## Tests

```bash
cd services/core-api && ./mvnw verify
```

Runs unit tests and integration tests. The integration tests need Docker: they start PostgreSQL through Testcontainers, connect as the unprivileged runtime role, and exercise the real HTTP API with Row-Level Security enabled.

```bash
cd apps/web && npm run lint && npm run typecheck && npm test
```

## Stopping

```bash
docker compose down
```

Add `-v` to also delete the database volume.

## Repository layout

```text
apps/web                  React dashboard
apps/widget               Iframe customer widget and loader (M4)
services/core-api         Spring Boot modular monolith
services/ai-service       FastAPI RAG service (M3)
workers/document-worker   Celery ingestion worker (M2)
infrastructure            Database bootstrap, Docker and monitoring assets
docs                      Architecture, database, API, security and AI documentation
```

## Documentation

- [Implementation plan](docs/implementation-plan.md) and [milestone checklist](docs/milestones.md)
- [Architecture](docs/architecture/system-overview.md)
- [Database and tenant ownership](docs/database/erd.md)
- [API](docs/api/README.md)
- [Threat model](docs/security/threat-model.md) and [tenant isolation test evidence](docs/security/tenant-isolation.md)
- [Contributing and conventions](CONTRIBUTING.md)

## Demo credentials policy

No credentials are committed. There are no built-in accounts: register your own locally. `.env.example` holds placeholders only.

## Limitations

- **No email delivery.** Links are written to the development log.
- **Login rate limiting is in memory**, so it applies per API instance and resets on restart.
- **The API runs migrations at startup** and therefore holds the database owner's credentials. Separating migration from runtime is part of M7.
- **No application containers yet.** The API and dashboard run on the host; only PostgreSQL and Redis run in Compose.
- **No browser end-to-end test suite yet.** The flows were checked by hand in a browser; Playwright arrives with later milestones.
- **Workspace logos are a URL**, not an upload, until document storage exists in M2.
- **Platform administration has a table but no features** until M6.
- The LLM provider ([ADR-013](docs/architecture/adr/0013-llm-provider-behind-an-adapter.md)) and the local object-storage emulator ([ADR-004](docs/architecture/adr/0004-private-object-storage-with-backend-controlled-access.md)) are not chosen yet.
- This is an engineering project, not legal advice; privacy and consumer-protection requirements need review before any real customer data is used.
