# Contributing to Servora

## Workflow

Servora was built in milestones; what was built, and what is still open, is in [docs/milestones.md](docs/milestones.md). Keep changes small and reviewable, one concern per pull request.

1. Branch from an up-to-date `main`.
2. Read the code, migrations and docs around the change, and the relevant [decision records](docs/architecture/adr/README.md).
3. Make the change, with tests alongside it.
4. Run the formatters, linters, tests and builds for every part you touched.
5. Review the diff for secrets, unrelated edits and tenant queries without a tenant filter.
6. Update the documentation and `.env.example` where behaviour or configuration changed.
7. Open a pull request with the commands you ran, their actual results, and any known limitations.

## Branches, commits and pull requests

- `main` is always releasable. Changes arrive through pull requests.
- Branch names describe the change: `feat/<area>-<topic>`, `fix/<area>-<topic>`, `docs/<topic>`.
- Commit messages follow Conventional Commits: `feat(auth): rotate refresh tokens`. Types: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`, `ci`, `build`.
- Reference requirement IDs where they apply: `feat(knowledge): validate upload content type (FR-014)`.
- Releases are tagged per milestone: `v0.1-m1` through `v0.7-m7`.

## Tenant isolation rules

These are release blockers. A pull request that breaks one is not merged.

- Every tenant-owned table has `tenant_id UUID NOT NULL`, a `UNIQUE (tenant_id, id)` constraint, tenant-aware foreign keys, and Row-Level Security enabled and forced.
- Tenant context comes from validated membership or a server-side widget-key lookup. A tenant ID from a URL, request body, document or model output is never proof of access.
- No repository method fetches a tenant-owned row by ID alone.
- The runtime database role does not own tables and does not have `BYPASSRLS`.
- Every new resource family ships with a cross-tenant negative test using two tenants.
- Public widget tokens cannot reach any `/api/v1/workspaces/**` endpoint.

## Coding conventions

### All code

- UTC timestamps in storage; convert to the tenant timezone for display.
- Explicit enums for finite states. Transactions for multi-row state changes.
- Validate input at boundaries. Return the standard error envelope with a machine-readable code and trace ID.
- Paginate list endpoints.
- Configuration comes from the environment and is validated at startup.
- Do not log secrets, full prompts, document contents or conversation bodies.
- Prefer the simplest maintainable implementation. Add an abstraction only when it protects a real boundary or enables testing.

### Java (core API)

- Base package `com.servora`, organised by feature: `auth`, `tenant`, `workspace`, `membership`, `assistant`, `knowledge`, `conversation`, `ticket`, `analytics`, `usage`, `notification`, `common`.
- Thin controllers; business rules live in services and domain types.
- Request and response DTOs with Bean Validation. JPA entities are never serialised directly.
- Schema changes only through Flyway migrations. Hibernate schema auto-update stays off.
- Tests: JUnit with Spring test support; integration tests run against real PostgreSQL through Testcontainers with RLS enabled.

### Python (AI service and worker)

- Typed code with Pydantic models at every boundary. Ruff for linting and formatting.
- Provider-specific code stays behind adapters. External calls have timeouts and bounded retries for transient failures only.
- Tests use the fake LLM provider. The test suite makes no paid API calls.
- Worker tasks are idempotent and retry-safe.

### TypeScript (dashboard and widget)

- Strict TypeScript, ESLint, typed API clients.
- Handle loading, empty, error, offline and permission-denied states.
- No secrets and no authorization decisions in the browser.
- Render AI and customer content as text. Never inject model output as HTML.
- Dashboard and widget styles stay isolated from each other.

## Secrets

Commit `.env.example` with placeholders only. Never commit provider keys, signing secrets, database passwords, customer documents or database dumps.

## Definition of done

- Acceptance criteria are met and covered by tests.
- Code is formatted, lints cleanly and builds.
- Relevant tests pass, and results are reported as they actually ran.
- Tenant scoping and authorization are reviewed.
- Migrations work from a clean database.
- Error and loading states are handled.
- Documentation and configuration examples are updated.
- Known limitations are written down.
