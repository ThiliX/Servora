// Runs the load test: starts the core API (through the browser tests' launcher, so development mail
// links are logged), seeds one workspace through the real API, runs k6 from a pinned Docker image,
// and writes the summary and the machine profile to tests/load/results/.
//
// Needs the Compose stack running (docker compose up -d) and port 8080 free.
// Usage: node tests/load/run.mjs [virtual users, default 20]
import { spawn, spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { cpus, platform, release, totalmem } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const API = "http://localhost:8080";
const SITE = "http://localhost:5181";
const LOG = join(here, "..", "e2e", ".artifacts", "api.log");
const RESULTS = join(here, "results");
const VUS = Number(process.argv[2] ?? 20);
const RUN = Date.now().toString(36);
const K6_IMAGE = "grafana/k6:1.3.0";

const api = spawn(process.execPath, [join(here, "..", "e2e", "scripts", "start-api.mjs")], {
  stdio: ["ignore", "ignore", "inherit"],
  env: {
    ...process.env,
    // Every request comes from this one machine, so the per-address limits meant for the public
    // internet are lifted for the run. The limits themselves are tested in the core API suite.
    SERVORA_WIDGET_CONFIGPERIPPERMINUTE: "1000000",
    SERVORA_WIDGET_SESSIONSPERIPPERHOUR: "1000000",
    SERVORA_WIDGET_MESSAGESPERIPPERMINUTE: "1000000",
    SERVORA_AUTH_REGISTRATIONSPERADDRESSPERHOUR: "1000000",
  },
});

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitForHealth() {
  for (let i = 0; i < 150; i++) {
    try {
      if ((await fetch(`${API}/readyz`)).ok) {
        return;
      }
    } catch {
      // Not up yet.
    }
    await sleep(2_000);
  }
  throw new Error("The core API did not become healthy");
}

async function call(method, path, body, token, headers = {}) {
  const response = await fetch(API + path, {
    method,
    headers: {
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${method} ${path} -> ${response.status} ${text}`);
  }
  return text && response.headers.get("content-type")?.includes("json") ? JSON.parse(text) : text;
}

async function seed() {
  const email = `load-${RUN}@example.test`;
  const password = "load-correct-horse-battery";
  const before = readFileSync(LOG, "utf8").length;
  await call("POST", "/api/v1/auth/register", { email, password, display_name: "Load Test" });
  let link;
  for (let i = 0; i < 30 && !link; i++) {
    link = /DEV MAIL verification link: (\S+)/.exec(readFileSync(LOG, "utf8").slice(before))?.[1];
    await sleep(500);
  }
  await call("POST", "/api/v1/auth/verify-email", { token: new URL(link).hash.replace("#token=", "") });
  const token = (await call("POST", "/api/v1/auth/login", { email, password })).access_token;
  const workspace = (await call("POST", "/api/v1/workspaces", { name: "Load Test", slug: `load-${RUN}` }, token)).id;
  await call("PUT", `/api/v1/workspaces/${workspace}/widget/allowed-origins`, { origins: [SITE] }, token);
  const widgetKey = (await call("GET", `/api/v1/workspaces/${workspace}/widget`, undefined, token)).public_key;

  // Twenty conversations with a customer message and the assistant's reply; every fourth is
  // escalated, so the ticket queue and notifications have content.
  const conversations = [];
  for (let i = 0; i < 20; i++) {
    const session = await call("POST", `/api/v1/public/widgets/${widgetKey}/sessions`, undefined, undefined, { Origin: SITE });
    await call("POST", "/api/v1/public/conversation/messages", { text: `Question number ${i}?` }, session.token, {
      "Idempotency-Key": `load-${RUN}-${i}`,
    });
    if (i % 4 === 0) {
      await call("POST", "/api/v1/public/conversation/handoff", undefined, session.token);
    }
    conversations.push(session.conversation_id);
  }
  return { token, workspace, widgetKey, conversations };
}

function profile() {
  const cpu = cpus();
  return {
    date: new Date().toISOString(),
    os: `${platform()} ${release()}`,
    cpu: `${cpu[0]?.model.trim()} (${cpu.length} logical cores)`,
    memory_gb: Math.round(totalmem() / 1024 ** 3),
    virtual_users: VUS,
    k6: K6_IMAGE,
    notes: "API on the host (Java 25, JVM defaults), PostgreSQL and Redis in Docker Desktop, k6 in Docker reaching the host through host.docker.internal.",
  };
}

try {
  await waitForHealth();
  const data = await seed();
  mkdirSync(RESULTS, { recursive: true });
  const k6 = spawnSync(
    "docker",
    [
      "run", "--rm",
      "-v", `${RESULTS}:/results`,
      "-v", `${here}:/scripts:ro`,
      "-e", "BASE_URL=http://host.docker.internal:8080",
      "-e", `TOKEN=${data.token}`,
      "-e", `WORKSPACE=${data.workspace}`,
      "-e", `WIDGET_KEY=${data.widgetKey}`,
      "-e", `SITE=${SITE}`,
      "-e", `CONVERSATIONS=${JSON.stringify(data.conversations)}`,
      "-e", `VUS=${VUS}`,
      K6_IMAGE, "run", "--quiet", "--summary-export", `/results/summary-${VUS}.json`, "/scripts/k6-script.js",
    ],
    { stdio: "inherit", env: { ...process.env, MSYS_NO_PATHCONV: "1" } },
  );
  writeFileSync(join(RESULTS, "profile.json"), JSON.stringify(profile(), null, 2));
  process.exitCode = k6.status ?? 1;
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  api.kill();
  if (platform() === "win32") {
    // The API runs under cmd.exe and Maven; stop whatever listens on 8080.
    spawnSync("powershell", ["-NoProfile", "-Command",
      "Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }"]);
  }
}
