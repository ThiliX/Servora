# Running the demo

From a fresh clone to two businesses, each with its own documents, assistant, widget and agent, using only the commands below. The steps follow the MVP demonstration script; the browser suite in `tests/e2e` runs the same script automatically.

The demo uses the built-in demo documents only (`services/ai-service/evaluation/fixtures`). Never seed a deployment that holds real customers' data.

## 1. Prerequisites and configuration

Docker with Compose v2, Node.js 24, and the tools listed in the [README](../README.md#prerequisites). Then:

```bash
cp .env.example .env    # replace every placeholder; see the comments in the file
```

`SERVORA_LLM_PROVIDER=fake` (the default) answers with a deterministic stand-in and costs nothing. For real answers set it to `gemini` or `anthropic` with a key; questions and the retrieved passages are then sent to that provider.

## 2. Start Servora

The production profile runs everything in containers. Mail links are written to the API log, because email delivery is not built:

```bash
SERVORA_PROD_MAIL_LOG_LINKS=true docker compose -p servora-prod -f docker-compose.yml -f compose.prod.yml up -d --build --wait
```

The first build takes several minutes: the worker and AI service images include the embedding model.

## 3. Seed the two businesses

```bash
node scripts/seed-demo.mjs --compose-project servora-prod --api http://localhost:8000
```

In about a minute it creates, through the public API:

| | TechStore | HotelPro |
|---|---|---|
| Owner and agent | `demo-techstore-owner-<run>@example.test`, `…-agent-…` | `demo-hotelpro-owner-<run>@example.test`, `…-agent-…` |
| Assistant | TechStore Assistant, friendly, balanced | HotelPro Concierge, friendly, balanced |
| Documents | Returns and refunds, shipping, warranty and repairs | Booking and cancellation, check-in and facilities, pets and parking |
| Widget allowed on | `http://localhost:5181` | `http://localhost:5182` |

It prints the dashboard and demo page addresses. Each run registers four accounts; the production profile allows 10 sign-ups per address per hour, so run it at most twice an hour on one machine (the browser suite registers four more). Passwords are generated and written only to `.demo/seed-<run>.json`, which git ignores. Each run uses new addresses, so it can be repeated.

With the API running on the host instead (development), pass the API's log file: `node scripts/seed-demo.mjs --mail-log <file>`, with `SERVORA_MAIL_LOG_LINKS=true` set for the API.

## 4. Open the demo websites

```bash
node apps/widget/demo/serve.mjs
```

Open each "Demo page" address the seed printed: TechStore on port 5181, HotelPro on 5182. The address carries the widget key and the widget host.

## 5. Walk through the demonstration script

1. **Two tenants.** Sign in at <http://localhost:8000> as each owner (password from `.demo/seed-<run>.json`). Each sees only its own workspace.
2. **Agents.** Each workspace's Team page lists its owner and agent.
3. **Different documents.** Each Knowledge page lists its three documents as Ready.
4. **Indexes ready.** The seed waited for this; the Playground answers immediately.
5. **Widgets on their own pages.** The chat opens on each demo page. A widget key on the other company's page shows nothing: the API refuses it.
6. **Answers from each tenant's own documents.** On TechStore, ask "How long do I have to send something back?"; on HotelPro, "Can I bring my dog?". Each answer cites that company's document.
7. **Unsupported question.** Ask TechStore "Can I bring my dog?". It does not answer from HotelPro's documents: it says it cannot help and offers a colleague.
8. **Escalate, assign, take over.** Ask for a person in the chat. Sign in as the agent: the ticket is in the Inbox. Assign it, open the conversation and take over.
9. **No late AI reply.** After the takeover only the agent's replies reach the customer. The browser suite tests the race; by hand, send a customer message after the takeover and see that only the agent can answer.
10. **Internal note.** Add a note on the ticket. It never appears in the customer's chat or its history.
11. **Cross-tenant tests.** Run the core API's isolation suites and show the result:

    ```bash
    cd services/core-api
    ./mvnw test -Dtest='CrossTenantMatrixIntegrationTest,TenantIsolationIntegrationTest,RowLevelSecurityIntegrationTest,PooledConnectionIsolationIntegrationTest'
    ```

The Overview, Usage and Audit pages then show the activity of the walk-through.

## 6. Run the same script automatically

```bash
cd tests/e2e && npm ci && npx playwright install chromium
E2E_TARGET=prod npx playwright test
```

Use `SERVORA_LLM_PROVIDER=fake` for this: the suite checks exact behaviour. It also runs an accessibility check (axe, WCAG 2.1 AA) over the dashboard pages and the open chat.

## 7. Stop and clean up

```bash
docker compose -p servora-prod -f docker-compose.yml -f compose.prod.yml down -v   # deletes the demo data
```

Delete `.demo/` too if you no longer need the generated passwords.
