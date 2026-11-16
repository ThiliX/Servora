# Version matrix

Verified on **2026-11-16** against the sources named in each row. These are the versions to pin when each component is scaffolded. Re-verify a row before changing it, and record the change here.

## How compatibility was checked

- **Spring Boot:** the official [system requirements page](https://docs.spring.io/spring-boot/system-requirements.html) states that Spring Boot 4.1.2 requires at least Java 17 and is compatible up to and including Java 27, with Maven 3.6.3 or later. Java 25 is inside that range.
- **Spring-managed libraries:** versions come from the `spring-boot-dependencies` 4.1.2 BOM on Maven Central. Do not override them without a documented reason.
- **Python packages:** latest stable release and `requires_python` from PyPI.
- **npm packages:** latest stable release, `engines` and `peerDependencies` from the npm registry.
- **Container images:** tags listed on Docker Hub.

## Runtime and build tools

| Tool | Pinned | Local machine | Notes |
|---|---|---|---|
| Java | 25 (LTS) | 25.0.1 | Supported by Spring Boot 4.1.2 (range 17–27) |
| Maven | via Maven Wrapper 3.3.4 | not installed | Boot needs Maven 3.6.3+; `mvnw` is committed in M1, so no global install is required |
| Node.js | 24.x (LTS "Krypton") | 24.13.0 | Satisfies Vite 8 (`^20.19.0 \|\| >=22.12.0`), Vitest 5 (`^24.0.0`), React Router 8 (`>=22.22.0`) |
| npm | 11.x | 11.6.2 | |
| Python | 3.13 (container) | 3.11.4 on host | Services run in `python:3.13.18-slim-bookworm`; 3.13 is supported by FastAPI, Celery 5.7 and PyTorch 2.15. Host Python is not used to run services |
| Docker Engine | 29.x | 29.5.3 | |
| Docker Compose | v5.x | v5.1.4 | |

## Core API (Java)

| Component | Version | Source |
|---|---|---|
| Spring Boot | 4.1.2 | Maven Central, latest stable |
| Spring Framework | 7.0.10 | Boot BOM |
| Spring Security | 7.1.2 | Boot BOM |
| Spring Data | 2026.0.2 | Boot BOM |
| Hibernate ORM | 7.4.13.Final | Boot BOM |
| Flyway | 12.4.0 | Boot BOM (13.x exists but is not Boot-managed; stay on the managed line) |
| PostgreSQL JDBC | 42.7.14 | Boot BOM |
| HikariCP | 7.0.2 | Boot BOM |
| JUnit Jupiter | 6.0.3 | Boot BOM |
| Testcontainers | 2.0.5 | Boot BOM |
| Micrometer | 1.17.2 | Boot BOM |
| OpenTelemetry | 1.62.0 | Boot BOM |
| springdoc-openapi | 3.2.0 | Maven Central; the project README states the 3.x line targets Spring Boot 4.x |

## AI service and document worker (Python)

| Package | Version | Requires Python |
|---|---|---|
| FastAPI | 0.163.1 | >=3.11 |
| Pydantic | 2.14.3 | >=3.10 |
| Uvicorn | 0.54.0 | >=3.10 |
| Celery | 5.7.0 | >=3.10 (classifiers list up to 3.14) |
| redis (client) | 8.1.0 | >=3.10 |
| psycopg | 3.3.9 | >=3.10 |
| pgvector (client) | 0.5.1 | >=3.10 |
| sentence-transformers | 6.3.0 | >=3.10 |
| PyTorch | 2.15.0 | >=3.11 |
| pypdf | 7.1.0 | >=3.10 |
| pytest | 9.2.0 | >=3.10 |
| Ruff | 0.17.5 | |

## Frontend (dashboard and widget)

| Package | Version | Notes |
|---|---|---|
| React | 19.3.1 | |
| Vite | 8.3.8 | |
| @vitejs/plugin-react | 6.3.0 | Peer: `vite ^8.0.0` |
| Tailwind CSS | 4.3.5 | With `@tailwindcss/vite` 4.3.5 (peer allows Vite 8) |
| TypeScript | **6.0.3** | Not 7.0.2: `typescript-eslint` 8.72.2 declares peer `typescript >=4.8.4 <6.1.0` |
| ESLint | 10.15.0 | Allowed by `typescript-eslint` 8.72.2 |
| typescript-eslint | 8.72.2 | |
| Vitest | 5.1.0 | Peer: `vite ^6.4.0 \|\| ^7 \|\| ^8` |
| React Router | 8.5.1 | Needs Node >=22.22.0 |
| Playwright | 1.65.1 | |

## Infrastructure images

| Image | Tag | Notes |
|---|---|---|
| PostgreSQL + pgvector | `pgvector/pgvector:0.8.7-pg18-bookworm` | pgvector 0.8.7 on PostgreSQL 18. PostgreSQL 18 images keep data under `/var/lib/postgresql`, which is where the volume is mounted |
| Redis | `redis:8.8.4-alpine` | Latest stable line; 8.12 is still a release candidate |
| Python base | `python:3.13.18-slim-bookworm` | Used from M2 |
| Object storage emulator | **not chosen** | `minio/minio` is no longer published on Docker Hub (the tags API returns 404). Choose an S3-compatible emulator in M2; see ADR-004 |

## Known limits of this check

- The two infrastructure images were started locally on 2026-11-16: both reported healthy, `CREATE EXTENSION vector` succeeded on PostgreSQL 18.7 with pgvector 0.8.7, and Redis 8.8.4 answered `PING`. That is the only part of this matrix exercised so far.
- Versions were read from registries and the Spring documentation. Nothing has been installed or built yet, so cross-package compatibility beyond the declared peer ranges is unproven until each component is scaffolded and its tests run.
- TypeScript stays on 6.0.x until `typescript-eslint` supports 7.
- The LLM provider SDK is not listed because the provider is still open (ADR-013).
