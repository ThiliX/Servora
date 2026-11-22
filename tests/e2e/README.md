# Browser tests

The MVP demonstration script in a real browser, against the real local stack, with Playwright.

| Step | What is checked |
|---|---|
| 1 | Two owners register through the dashboard, verify their email and create TechStore and HotelPro |
| 2 | Each owner invites an agent, who registers and accepts the invitation |
| 3–4 | Each owner uploads a different policy document and waits until the worker has indexed it |
| 5 | Each widget is allowed on its own demo website; a key does not work on the other website |
| 6 | Each widget answers from its own document, with the document cited |
| 7 | The TechStore widget, asked about hotel check-in, does not answer from HotelPro's document |
| 8 | The customer asks for a person; the owner assigns the agent, who takes over |
| 9 | After the takeover the customer gets no AI reply, and the agent's reply reaches the widget |
| 10 | An internal note is shown neither in the widget nor in the public history API |
| 10b | Neither workspace's people can open the other workspace in the dashboard |
| M6 | Overview, Usage and Audit log show the workspace's activity; agents do not see those pages |
| Accessibility | axe (WCAG 2.1 A and AA rules) finds no serious or critical issue on the sign-in and sign-up pages, 11 workspace pages, and the demo website with the chat open. Moderate and minor findings are not enforced |

Step 9 checks the result of a takeover. The race between a takeover and an AI reply being generated is tested deterministically in the core API suite (`anAiReplyIsNeverDeliveredAfterATakeover`, 24 rounds per run), and step 11 of the script, the cross-tenant test output, is that suite (`./mvnw test`).

## Run

1. Start the infrastructure, worker and AI service with the fake model, so answers are deterministic and nothing is sent to a model provider:

   ```bash
   SERVORA_LLM_PROVIDER=fake docker compose up -d --wait
   ```

2. Make sure nothing listens on port 8080. Playwright starts the core API itself, with development mail links written to `tests/e2e/.artifacts/api.log`, which is how the tests read verification and invitation links. It also starts the dashboard (5173), widget (5174) and demo websites (5181, 5182), or reuses ones already running.

3. Install and run:

   ```bash
   cd tests/e2e
   npm ci
   npx playwright install chromium
   npx playwright test
   ```

Each run creates new users and workspaces with a unique suffix in the local database. The launcher raises the sign-up limit per address for the local API, because each run signs up four users from one machine.

### Against the production profile

```bash
SERVORA_LLM_PROVIDER=fake SERVORA_PROD_MAIL_LOG_LINKS=true \
  docker compose -p servora-prod -f docker-compose.yml -f compose.prod.yml up -d --build --wait
cd tests/e2e && E2E_TARGET=prod npx playwright test
```

The tests then use the gateway on 8000 and the widget host on 8001, load the built widget loader the way an embed snippet does, and read mail links from the core API container's log. This is what the CI job "Production stack" runs.

To reuse an API you started yourself, start it with `SERVORA_MAIL_LOG_LINKS=true` and `LOGGING_FILE_NAME` pointing at a file, then run with `E2E_REUSE_API=true E2E_API_LOG=<that file>`.

## Results

Local runs on 2026-11-17: 11 of 11 steps passed in each of two consecutive runs against the development setup (about 55 seconds each), and in three runs against the production profile (about 70 seconds each).
