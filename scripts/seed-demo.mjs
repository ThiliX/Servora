#!/usr/bin/env node
// Builds the demonstration data through the public API, exactly as a person would in the dashboard:
// two businesses, TechStore and HotelPro, each with an owner, an invited agent, an assistant, three
// documents indexed by the real worker, and a widget allowed on its demo website.
//
//   node scripts/seed-demo.mjs --mail-log <API log file>          # API on the host (development)
//   node scripts/seed-demo.mjs --compose-project servora-prod \
//     --api http://localhost:8000                                 # production profile
//
// The API must write verification and invitation links to its log (SERVORA_MAIL_LOG_LINKS=true for
// a host API, SERVORA_PROD_MAIL_LOG_LINKS=true for the production profile), because email delivery
// is not built. Every run uses new addresses, so it can be repeated. Generated passwords are written
// to .demo/seed-<run>.json (git-ignored) and never printed. Demo data only: never point this at a
// deployment holding real customers' data.
import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const fixtures = join(root, "services", "ai-service", "evaluation", "fixtures");

const { values: options } = parseArgs({
  options: {
    api: { type: "string", default: "http://localhost:8080" },
    dashboard: { type: "string" },
    "mail-log": { type: "string" },
    "compose-project": { type: "string" },
    run: { type: "string", default: Date.now().toString(36) },
    out: { type: "string" },
  },
});
const API = options.api.replace(/\/$/, "");
const DASHBOARD = (options.dashboard ?? (API.endsWith(":8000") ? API : "http://localhost:5173")).replace(/\/$/, "");
const RUN = options.run;
if (!/^[a-z0-9]{3,20}$/.test(RUN)) throw new Error("--run must be 3-20 lowercase letters or digits");
if (!options["mail-log"] && !options["compose-project"]) {
  throw new Error("Pass --mail-log <file> or --compose-project <name> so verification links can be read");
}

const companies = [
  {
    key: "techstore",
    name: "TechStore",
    industry: "Consumer electronics retail",
    site: "http://localhost:5181",
    assistant: {
      name: "TechStore Assistant",
      welcomeMessage: "Hi! I can help with orders, shipping, returns and warranties.",
      fallbackMessage: "I'm not sure about that one. Let me get a colleague to help you.",
    },
  },
  {
    key: "hotelpro",
    name: "HotelPro",
    industry: "Hospitality",
    site: "http://localhost:5182",
    assistant: {
      name: "HotelPro Concierge",
      welcomeMessage: "Welcome to HotelPro. Ask me about bookings, check-in, parking or pets.",
      fallbackMessage: "I'd rather not guess. A member of our front desk team will follow up with you.",
    },
  },
];

// ---------------------------------------------------------------------------------------------
// HTTP and mail links

async function call(method, path, { token, body, form, expect = [200, 201, 202, 204] } = {}) {
  const headers = { "X-Servora-Client": "seed-demo" };
  if (token) headers.Authorization = `Bearer ${token}`;
  let payload;
  if (form) {
    payload = form;
  } else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }
  const response = await fetch(`${API}/api/v1${path}`, { method, headers, body: payload });
  const text = await response.text();
  if (!expect.includes(response.status)) {
    let code = "";
    try {
      const error = JSON.parse(text).error ?? {};
      // Codes and field names only: the API never echoes submitted values.
      code = [error.code, ...(error.details ?? []).map((detail) => detail.field)].filter(Boolean).join(" ");
    } catch {}
    throw new Error(`${method} ${path} returned ${response.status} ${code}`.trim());
  }
  return text ? JSON.parse(text) : null;
}

function readLog() {
  if (options["mail-log"]) {
    return existsSync(options["mail-log"]) ? readFileSync(options["mail-log"], "utf8") : "";
  }
  return execFileSync(
    "docker",
    ["compose", "-p", options["compose-project"], "-f", join(root, "docker-compose.yml"), "-f", join(root, "compose.prod.yml"), "logs", "--no-color", "core-api"],
    { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 },
  );
}

/** Runs an action that sends a mail to `email`, and returns the token from the link the API logged. */
async function mailToken(kind, action) {
  const before = readLog().length;
  await action();
  // Plain-text or JSON log lines; the link ends at whitespace or a quote.
  const pattern = new RegExp(`DEV MAIL ${kind} link: ([^\\s"]+)`, "g");
  for (let attempt = 0; attempt < 30; attempt++) {
    const added = readLog().slice(before);
    const links = [...added.matchAll(pattern)].map((match) => match[1]);
    if (links.length > 0) {
      const link = links[links.length - 1];
      const token = /token=([^&#\s"]+)/.exec(link)?.[1];
      if (token) return decodeURIComponent(token);
    }
    await new Promise((done) => setTimeout(done, 500));
  }
  throw new Error(`No ${kind} link appeared in the API log. Is mail link logging switched on?`);
}

// ---------------------------------------------------------------------------------------------
// Steps

function newPerson(company, role) {
  return {
    displayName: role === "OWNER" ? `${company.name} Owner` : `${company.name} Agent`,
    email: `demo-${company.key}-${role.toLowerCase()}-${RUN}@example.test`,
    // 24 random bytes: shown nowhere, stored only in the git-ignored output file.
    password: randomBytes(24).toString("base64url"),
  };
}

async function registerAndSignIn(person) {
  const token = await mailToken("verification", () =>
    call("POST", "/auth/register", {
      body: { email: person.email, password: person.password, display_name: person.displayName },
    }),
  );
  await call("POST", "/auth/verify-email", { body: { token } });
  const session = await call("POST", "/auth/login", { body: { email: person.email, password: person.password } });
  return session.access_token;
}

async function uploadAndWait(token, workspaceId, directory) {
  const ids = [];
  for (const file of readdirSync(directory).filter((name) => name.endsWith(".md")).sort()) {
    const form = new FormData();
    form.append("file", new Blob([readFileSync(join(directory, file))], { type: "text/markdown" }), file);
    const document = await call("POST", `/workspaces/${workspaceId}/knowledge/documents`, { token, form });
    ids.push({ id: document.id, file });
  }
  const deadline = Date.now() + 5 * 60_000;
  for (const document of ids) {
    for (;;) {
      const current = await call("GET", `/workspaces/${workspaceId}/knowledge/documents/${document.id}`, { token });
      if (current.status === "READY") break;
      if (current.status === "FAILED") throw new Error(`${document.file} failed to process: ${current.error_code}`);
      if (Date.now() > deadline) throw new Error(`${document.file} was not ready after 5 minutes. Is the worker running?`);
      await new Promise((done) => setTimeout(done, 2_000));
    }
  }
  return ids.map((document) => document.file);
}

/**
 * The demo website's address. In development it loads the widget from the Vite dev server; with the
 * production profile it loads the built loader from the widget host, which also forwards the API.
 */
function demoPage(site, widget) {
  const page = `${site}/?key=${widget.public_key}`;
  if (!options["compose-project"]) return page;
  const host = new URL(widget.loader_url).origin;
  return `${page}&widget=${host}&loader=/servora-widget.js&api=${host}`;
}

async function seed(company) {
  const owner = newPerson(company, "OWNER");
  const agent = newPerson(company, "AGENT");

  const ownerToken = await registerAndSignIn(owner);
  const workspace = await call("POST", "/workspaces", {
    token: ownerToken,
    body: { name: company.name, slug: `${company.key}-${RUN}`, timezone: "UTC", default_language: "en" },
  });
  await call("PATCH", `/workspaces/${workspace.id}`, { token: ownerToken, body: { industry: company.industry } });

  await call("PUT", `/workspaces/${workspace.id}/assistant`, {
    token: ownerToken,
    body: {
      name: company.assistant.name,
      welcome_message: company.assistant.welcomeMessage,
      fallback_message: company.assistant.fallbackMessage,
      language: "en",
      tone: "FRIENDLY",
      automation_enabled: true,
      confidence_policy: "BALANCED",
    },
  });

  const documents = await uploadAndWait(ownerToken, workspace.id, join(fixtures, company.key));

  const widget = await call("PUT", `/workspaces/${workspace.id}/widget/allowed-origins`, {
    token: ownerToken,
    body: { origins: [company.site] },
  });

  const agentToken = await registerAndSignIn(agent);
  const invitationToken = await mailToken("invitation", () =>
    call("POST", `/workspaces/${workspace.id}/invitations`, {
      token: ownerToken,
      body: { email: agent.email, role: "AGENT" },
    }),
  );
  await call("POST", "/invitations/accept", { token: agentToken, body: { token: invitationToken } });

  return {
    company: company.name,
    workspace_id: workspace.id,
    dashboard: `${DASHBOARD}/w/${workspace.id}/`,
    documents,
    widget_key: widget.public_key,
    loader_url: widget.loader_url,
    demo_page: demoPage(company.site, widget),
    owner: { email: owner.email, password: owner.password },
    agent: { email: agent.email, password: agent.password },
  };
}

// ---------------------------------------------------------------------------------------------

const results = [];
for (const company of companies) {
  process.stdout.write(`Seeding ${company.name}... `);
  results.push(await seed(company));
  process.stdout.write("done\n");
}

const out = resolve(options.out ?? join(root, ".demo", `seed-${RUN}.json`));
mkdirSync(dirname(out), { recursive: true });
writeFileSync(
  out,
  JSON.stringify({ run: RUN, api: API, created_at: new Date().toISOString(), workspaces: results }, null, 2) + "\n",
  { mode: 0o600 },
);

console.log("");
for (const result of results) {
  console.log(`${result.company}`);
  console.log(`  Dashboard:  ${result.dashboard}`);
  console.log(`  Owner:      ${result.owner.email}`);
  console.log(`  Agent:      ${result.agent.email}`);
  console.log(`  Documents:  ${result.documents.join(", ")} (ready)`);
  console.log(`  Demo page:  ${result.demo_page}`);
}
console.log("");
console.log(`Passwords and IDs: ${out} (git-ignored; keep it local).`);
console.log("Demo websites: node apps/widget/demo/serve.mjs");
