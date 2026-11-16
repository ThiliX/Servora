# Servora core API

Spring Boot modular monolith and system of record. Base package `com.servora`, organised by feature:

`auth`, `tenant`, `workspace`, `membership`, `assistant`, `knowledge`, `conversation`, `ticket`, `analytics`, `usage`, `notification`, `common`.

Flyway migrations live in `src/main/resources/db/migration` and own the whole schema ([ADR-008](../../docs/architecture/adr/0008-flyway-in-the-core-api-owns-the-entire-database-schema.md)).

Not implemented yet. Scaffolded in M1.
