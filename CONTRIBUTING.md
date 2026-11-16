# Contributing to Servora

## Workflow

Work proceeds one milestone at a time, as defined in [docs/implementation-plan.md](docs/implementation-plan.md). Do not start a later milestone before the current one passes its exit gate.

For each milestone:

1. Inspect the repository and current branch.
2. Read the relevant docs, migrations and code.
3. Summarise the current state and any conflicts with the specification.
4. Propose a small task plan and the files expected to change.
5. Implement only that milestone.
6. Add or update tests alongside the code.
7. Run formatters, linters, tests and builds.
8. Review the diff for secrets, unrelated edits and unscoped tenant queries.
9. Update documentation and `.env.example`.
10. Report what was done, the commands run, the real test results and known limitations.

## Branches, commits and pull requests

- `main` is always releasable. Changes arrive through pull requests.
- One branch per milestone: `milestone/m1-identity-tenancy`. Smaller pieces may use `feat/<area>-<topic>` or `fix/<area>-<topic>` branched from it.
- Commit messages follow Conventional Commits: `feat(auth): rotate refresh tokens`. Types: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`, `ci`, `build`.
- Reference requirement IDs from the handbook where they apply: `feat(knowledge): validate upload content type (FR-014)`.
- Tag each passed milestone gate: `v0.1-m1` through `v1.0-m7`.

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
