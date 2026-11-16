# Servora core API

Spring Boot modular monolith and system of record. Base package `com.servora`, organised by feature.

| Package | Contents |
|---|---|
| `auth` | Users, registration, email verification, login, refresh-token rotation |
| `workspace` | Tenants (workspaces) and their settings |
| `membership` | Memberships, roles, the permission matrix and `WorkspaceAccess` |
| `invitation` | Invitations and acceptance |
| `audit` | Append-only audit events |
| `common` | Error envelope, correlation IDs, security configuration, tenant context, mail gateway |

## Rules for tenant-scoped code

1. Start every workspace operation with `WorkspaceAccess.authorize(userId, workspaceId, permission)` inside a transaction. It validates membership and sets the tenant context.
2. Pass the tenant ID from the returned `WorkspaceContext` to every repository call. Do not add repository methods that load a tenant-owned row by ID alone.
3. Never set the PostgreSQL context settings anywhere except `TenantContext`.
4. A new tenant-owned table needs `tenant_id NOT NULL`, `UNIQUE (tenant_id, id)`, tenant-aware foreign keys, RLS enabled and forced, policies, minimal grants, and cross-tenant tests. `RowLevelSecurityIntegrationTest` fails if RLS is missing.

## Run

```bash
./mvnw spring-boot:run
```

Configuration comes from the `.env` file at the repository root (see the root README).

## Test

```bash
./mvnw verify
```

Integration tests need Docker. They run the real application over HTTP against PostgreSQL with RLS, connected as the unprivileged runtime role.

Migrations live in `src/main/resources/db/migration` and own the whole schema ([ADR-008](../../docs/architecture/adr/0008-flyway-in-the-core-api-owns-the-entire-database-schema.md)).
