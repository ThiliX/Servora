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
| Bouncy Castle (`bcprov-jdk18on`) | 1.87 | Maven Central; required by the Argon2 password encoder in Spring Security |
| Maven (via wrapper) | 3.10.0 | Chosen by Spring Initializr for Boot 4.1.2 |
| AWS SDK for Java v2 (`s3`) | 2.59.1 | Maven Central; S3-compatible document storage |
| Spring Data Redis (`spring-boot-starter-data-redis`) | Boot BOM | Shared rate-limit counters, with the Lettuce client |
| `spring-boot-starter-opentelemetry` | Boot BOM | Micrometer Tracing with the OpenTelemetry bridge and OTLP exporter (ADR-022) |
| `micrometer-registry-prometheus` | Boot BOM (1.17.2) | Metrics on the management port |
| datasource-micrometer (`datasource-micrometer-spring-boot`) | 2.3.0 | JDBC spans. 2.3.1 (for Boot 4.1.2) was three days old when chosen; 2.3.0 targets Boot 4.1.1 and works with 4.1.2 |

## AI service and document worker (Python)

| Package | Version | Requires Python |
|---|---|---|
| FastAPI | 0.163.1 | >=3.11 |
| Pydantic | 2.14.3 | >=3.10 |
| Uvicorn | 0.54.0 | >=3.10 |
| Celery | 5.7.0 | >=3.10 (classifiers list up to 3.14) |
| redis (client) | 8.1.0 | >=3.10 |
| psycopg | 3.3.9 | >=3.10 |
| pgvector (client) | 0.5.1 | >=3.10. Not used: vectors are passed as text and cast in SQL |
| sentence-transformers | 6.3.0 | >=3.10 |
| PyTorch | 2.15.0 | >=3.11 |
| pypdf | 7.1.0 | >=3.10 |
| boto3 | 1.44.3 | |
| google-genai | 3.4.0 | >=3.10. Google Gemini provider SDK (ADR-013 amendment); default model `gemini-3.5-flash-lite` |
| anthropic | 2.9.0 | >=3.10. LLM provider SDK for the AI service (ADR-013); default model `claude-opus-5-5` |
| PyJWT | 2.15.1 | |
| prometheus-client | 0.26.0 | Metrics; multi-process mode in the Celery worker (ADR-022) |
| opentelemetry-sdk, opentelemetry-exporter-otlp-proto-http | 1.45.2 | Traces (ADR-022) |
| opentelemetry-instrumentation-fastapi, -psycopg, -celery | 0.66b2 | Released with SDK 1.45.2. The instrumentation packages are versioned as betas upstream |
| pydantic-settings | 2.16.3 | |
| pytest | 9.2.0 | >=3.10 |
| testcontainers | 4.15.0 | Test only |
| httpx | 0.28.1 | Test only. Starlette's test client now warns that it prefers `httpx2`; revisit when FastAPI is upgraded |
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
| Playwright | 1.65.1 | Not installed yet |
| jsdom | 29.1.1 | Test environment |
| Testing Library (React / jest-dom / user-event) | 16.3.3 / 7.0.1 / 14.6.12 | |
| eslint-plugin-react-hooks | 7.1.1 | |

## Infrastructure images

| Image | Tag | Notes |
|---|---|---|
| PostgreSQL + pgvector | `pgvector/pgvector:0.8.7-pg18-bookworm` | pgvector 0.8.7 on PostgreSQL 18. PostgreSQL 18 images keep data under `/var/lib/postgresql`, which is where the volume is mounted |
| Redis | `redis:8.8.4-alpine` | Latest stable line; 8.12 is still a release candidate |
| Python base | `python:3.13.18-slim-bookworm` | Used from M2 |
| Object storage emulator | `versity/versitygw:v1.11.0` | Chosen in M2 because `minio/minio` is no longer published on Docker Hub. See ADR-004 |
| Worker image | built from `python:3.13.18-slim-bookworm` | With CPU-only `torch==2.15.0` and the pinned embedding model; about 1.7 GB |

## Container images (M7)

Base images are pinned to tags at least two weeks old and receive the distribution's security updates at build time.

| Image | Tag | Used for |
|---|---|---|
| Eclipse Temurin JDK / JRE | `25.0.4_7-jdk-noble` / `25.0.4_7-jre-noble` | Building and running the core API and the migrations container |
| Node.js | `24.13.0-alpine3.22` | Building the dashboard and widget (not shipped) |
| nginx (unprivileged) | `1.30.6-alpine3.24`, with nginx `1.30.7-r1` from nginx.org | Serving the dashboard and gateway, and the widget host. nginx 1.30.7 fixes six high and critical advisories published 2026-11-18, one of them in the resolver these configurations use; it is installed over the base image (unused modules removed) because no 1.30.7 image exists yet. An exception to the two-week rule for a security fix; revert to a plain base image once a 1.30.7 tag is two weeks old |
| Python | `3.13.18-slim-bookworm` | Worker and AI service (unchanged) |
| Alpine | `3.22.2` | Backup and restore of the storage volume |
| Trivy | `aquasec/trivy:0.58.1` | Dependency and image scanning |
| gitleaks | `zricethezav/gitleaks:v8.21.2` | Secret scanning |
| k6 | `grafana/k6:1.3.0` | Load test |
| Playwright | `@playwright/test` 1.64.1 | Browser tests |
| Prometheus | `prom/prometheus:v3.16.0` | Optional observability overlay (ADR-022) |
| Jaeger | `jaegertracing/jaeger:2.23.0` | Optional observability overlay: OTLP receiver and trace UI, in memory |

## Known limits of this check

- **M4 (2026-11-17):** Spring Data Redis was exercised against `redis:8.8.4-alpine` in the integration tests and the local stack. The widget app uses the dashboard's toolchain versions without React or Tailwind, and was built, tested and run in a browser.
- **M3 (2026-11-17):** the AI service was installed, tested and run on Python 3.13 in a container: FastAPI, psycopg, PyJWT, the embedding model for queries, and `anthropic` 2.9.0. The Anthropic SDK was exercised against a stubbed client only; no call to the live API was made. The AI service image is about 1.7 GB and shares its model layers with the worker image.
- **M2 (2026-11-16):** the Python worker rows were installed, tested and run on Python 3.13 in containers, including the real embedding model in an end-to-end upload. The storage emulator was exercised by both the Java and Python clients. FastAPI and sentence-transformers in the AI service (M3) remain unexercised beyond what the worker uses.
- On the development machine, `pip` on the host fails certificate verification for PyPI, so Python work runs in containers. CI installs normally.
- **M1 (2026-11-16):** the core API and dashboard versions above were installed, built and tested together: Java 25 with Spring Boot 4.1.2, Flyway, Hibernate, Testcontainers 2.0.5 against the pgvector image, and the frontend toolchain except Playwright. The Python rows remain unexercised.
- The two infrastructure images were started locally on 2026-11-16: both reported healthy, `CREATE EXTENSION vector` succeeded on PostgreSQL 18.7 with pgvector 0.8.7, and Redis 8.8.4 answered `PING`. That is the only part of this matrix exercised so far.
- Versions were read from registries and the Spring documentation. Nothing has been installed or built yet, so cross-package compatibility beyond the declared peer ranges is unproven until each component is scaffolded and its tests run.
- TypeScript stays on 6.0.x until `typescript-eslint` supports 7.
- The LLM provider SDK is not listed because the provider is still open (ADR-013).
