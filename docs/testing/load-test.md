# Load test

Handbook section 5 sets the target: P95 under 500 ms for non-AI API operations under a stated local load. This records how it was measured and what was found. AI answers are excluded: their time is the model's.

## How to run

```bash
docker compose up -d --wait
node tests/load/run.mjs 20     # number of virtual users
```

`tests/load/run.mjs` starts the core API, seeds one workspace through the real API (an owner, an allowed website, 20 widget conversations with a customer message and a reply, every fourth escalated to a ticket), and runs [`k6-script.js`](../../tests/load/k6-script.js) with k6 from the pinned image `grafana/k6:1.3.0`. Results are written to `tests/load/results/`.

The per-address limits for the public internet (widget configuration, sessions and messages, sign-up) are lifted for the run, because every request comes from one machine. Those limits are tested in the core API suite.

## Workload

Each virtual user repeatedly sends one request, chosen by weight, then waits 0.2 seconds. The mix follows what the dashboard and widget do most:

| Weight | Request |
|---|---|
| 3 | Ticket queue, conversation messages, notifications |
| 2 | Conversation list, workspace, widget configuration (public, with an allowed `Origin`) |
| 1 | Workspace list, documents, analytics (30 days), usage, audit log, adding an internal note (a write) |

Profile: 15 seconds ramping up to the target number of users (included in the results), 60 seconds at the target, 5 seconds down.

## Machine and setup

- AMD Ryzen 5 5600X (6 cores, 12 threads), 16 GB RAM, Windows 11 (build 26200).
- Core API on the host: Java 25, default JVM settings, connection pool of 10.
- PostgreSQL 18 with pgvector and Redis 8 in Docker Desktop, on the same machine.
- k6 in Docker Desktop, reaching the API through `host.docker.internal`, so each request also crosses Docker's network layer.
- The worker and AI service were running but idle.
- Small dataset: one workspace, about 20 conversations. Production data volumes were not simulated.

## Results (2026-11-17)

| Virtual users | Requests | Requests per second | Failed | Median | P95 | P99 | Max |
|---|---|---|---|---|---|---|---|
| 20 | 6,750 | 84 | 0 | 6.6 ms | **11.6 ms** | 21.9 ms | 41 ms |
| 100 | 33,471 | 418 | 0 | 7.6 ms | **15.0 ms** | 28.8 ms | 184 ms |
| 100 (first run) | 33,071 | 332 | 4 (0.012%) | 7.4 ms | 14.1 ms | 33.2 ms | 445 ms |

The target is met with a wide margin at both levels.

Per endpoint at 100 virtual users (second run):

| Endpoint | Median | P95 | P99 |
|---|---|---|---|
| Analytics (30 days) | 12.6 ms | 20.3 ms | 33.0 ms |
| Widget configuration | 11.5 ms | 18.8 ms | 39.1 ms |
| Add internal note | 10.4 ms | 18.1 ms | 31.1 ms |
| Usage | 9.3 ms | 16.5 ms | 30.9 ms |
| Conversation messages | 8.3 ms | 14.5 ms | 30.8 ms |
| Conversation list | 7.4 ms | 13.0 ms | 26.8 ms |
| Ticket queue | 7.0 ms | 11.9 ms | 24.8 ms |
| Audit log | 6.8 ms | 11.9 ms | 21.9 ms |
| Notifications | 6.6 ms | 11.8 ms | 23.5 ms |
| Documents | 6.7 ms | 11.4 ms | 25.4 ms |
| Workspace | 5.4 ms | 9.5 ms | 18.9 ms |
| Workspace list | 4.0 ms | 7.1 ms | 16.4 ms |

**The four failures in the first 100-user run** were one request each to usage, widget configuration, conversation messages and workspace. The API logged no error for any of them, and every endpoint's maximum latency in that run was about 400 ms at the same moment, which points to a pause outside the request handling: the JVM, Docker's network layer or the host. They did not recur in the second run, which also printed the status of any failure. The cause is not known.

Raw k6 summaries: [`tests/load/results/`](../../tests/load/results/).

## What this does not show

- Behaviour with production-sized data: thousands of conversations per workspace, many workspaces.
- Behaviour on server hardware, behind a proxy with TLS, or with more than one API instance.
- AI answer latency, which depends on the model provider.
- Saturation: the highest load tested was 418 requests per second, and the limit was not looked for.
