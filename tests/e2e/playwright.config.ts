import { defineConfig, devices } from "@playwright/test";

// "prod" runs against the production Compose profile, which must already be running.
const prod = process.env.E2E_TARGET === "prod";
const demoSites = {
  command: "node ../../apps/widget/demo/serve.mjs",
  url: "http://localhost:5181",
  reuseExistingServer: true,
  timeout: 30_000,
};

/**
 * Runs the MVP demonstration script in a real browser against the local stack: PostgreSQL, Redis,
 * storage, the document worker and the AI service from Docker Compose (with the fake model), plus
 * the core API, dashboard, widget and the two demo websites, which Playwright starts here.
 * See README.md in this folder.
 */
export default defineConfig({
  testDir: "./specs",
  // One story with shared state; its steps run in order.
  workers: 1,
  fullyParallel: false,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  retries: 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: prod ? "http://localhost:8000" : "http://localhost:5173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    ...devices["Desktop Chrome"],
  },
  webServer: prod
    ? [demoSites]
    : [
        {
          // The tests read development mail links from this API's log file, so an API that is
          // already running is reused only when asked to (see README.md).
          command: "node scripts/start-api.mjs",
          url: "http://localhost:8080/actuator/health",
          reuseExistingServer: process.env.E2E_REUSE_API === "true",
          timeout: 300_000,
          stdout: "ignore",
          stderr: "pipe",
        },
        {
          command: "npm --prefix ../../apps/web run dev -- --port 5173 --strictPort",
          url: "http://localhost:5173",
          reuseExistingServer: true,
          timeout: 120_000,
        },
        {
          command: "npm --prefix ../../apps/widget run dev -- --port 5174 --strictPort",
          url: "http://localhost:5174/widget.html",
          reuseExistingServer: true,
          timeout: 120_000,
        },
        demoSites,
      ],
});
