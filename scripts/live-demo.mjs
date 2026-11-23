#!/usr/bin/env node
// Runs the public demo on this machine, served through Tailscale Funnel (docs/operations/live-demo.md).
//
//   node scripts/live-demo.mjs start    # build and start; creates the demo data on the first run
//   node scripts/live-demo.mjs reset    # delete everything and create fresh demo data (nightly)
//   node scripts/live-demo.mjs stop     # stop the containers, keeping the data
//   node scripts/live-demo.mjs status   # the containers and the public addresses
//
// The public name is this machine's Tailscale name (for example pc.tail1234.ts.net), read from
// `tailscale status`, or --host <name>. The demo uses its own compose project (servora-demo), so a
// development stack and its data are never touched. Demo data only.
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, renameSync, rmSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const PROJECT = "servora-demo";
const FILES = ["docker-compose.yml", "compose.prod.yml", "compose.demo.yml"];
const PUBLIC_DIR = join(root, ".demo", "public");
const CONFIG = join(PUBLIC_DIR, "config.json");
const PORTS = { web: 8000, widget: 8001, site: 8002 };

const { values: options, positionals } = parseArgs({
  allowPositionals: true,
  options: { host: { type: "string" } },
});
const command = positionals[0] ?? "status";

function tailscaleHost() {
  if (options.host) return options.host.replace(/\.$/, "");
  // A fresh Windows install is not on PATH until a new terminal opens.
  const candidates = ["tailscale", "C:\\Program Files\\Tailscale\\tailscale.exe"];
  for (const executable of candidates) {
    try {
      const status = JSON.parse(execFileSync(executable, ["status", "--json"], { encoding: "utf8" }));
      const name = status?.Self?.DNSName?.replace(/\.$/, "");
      if (name) return name;
    } catch {}
  }
  throw new Error("Could not read this machine's Tailscale name. Is Tailscale running? Or pass --host <name>.ts.net");
}

function addresses(host) {
  return {
    web: `https://${host}`,
    widget: `https://${host}:8443`,
    site: `https://${host}:10000`,
  };
}

function compose(args, env = {}) {
  const files = FILES.flatMap((file) => ["-f", join(root, file)]);
  const result = spawnSync("docker", ["compose", "-p", PROJECT, ...files, ...args], {
    cwd: root,
    stdio: "inherit",
    // compose.demo.yml requires the public addresses even for commands that do not use them.
    env: { SERVORA_DEMO_WEB_ORIGIN: "https://unset", SERVORA_DEMO_WIDGET_ORIGIN: "https://unset", ...process.env, ...env },
  });
  if (result.status !== 0) throw new Error(`docker compose ${args.join(" ")} failed`);
}

function environment(host, { demoMode }) {
  const urls = addresses(host);
  return {
    SERVORA_DEMO_WEB_ORIGIN: urls.web,
    SERVORA_DEMO_WIDGET_ORIGIN: urls.widget,
    SERVORA_PROD_WEB_ORIGIN: urls.web,
    SERVORA_PROD_WIDGET_ORIGIN: urls.widget,
    SERVORA_DEMO_MODE: String(demoMode),
    // Verification and invitation links go to the API log only while the demo data is created.
    SERVORA_PROD_MAIL_LOG_LINKS: String(!demoMode),
    SERVORA_WEB_PORT: String(PORTS.web),
    SERVORA_WIDGET_PORT: String(PORTS.widget),
    SERVORA_DEMO_SITE_PORT: String(PORTS.site),
  };
}

function seed(host) {
  const urls = addresses(host);
  const pending = `${CONFIG}.new`;
  const result = spawnSync(
    process.execPath,
    [
      join(root, "scripts", "seed-demo.mjs"),
      "--compose-project", PROJECT,
      "--compose-file", "compose.demo.yml",
      "--api", `http://localhost:${PORTS.web}`,
      "--dashboard", urls.web,
      "--site-origin", urls.site,
      "--public-config", pending,
    ],
    // The seed script reads the API log with docker compose, which needs the same settings.
    { cwd: root, stdio: "inherit", env: { ...process.env, ...environment(host, { demoMode: false }) } },
  );
  if (result.status !== 0) throw new Error("Creating the demo data failed");
  renameSync(pending, CONFIG);
}

function reset(host) {
  mkdirSync(PUBLIC_DIR, { recursive: true });
  // Visitors see no demo accounts until the new ones exist.
  rmSync(CONFIG, { force: true });
  console.log("Removing the demo containers and their data...");
  compose(["down", "--volumes", "--remove-orphans"]);
  console.log("Starting with demo mode off, to create the demo data...");
  compose(["up", "-d", "--build", "--wait"], environment(host, { demoMode: false }));
  seed(host);
  console.log("Switching demo mode on...");
  compose(["up", "-d", "--wait", "core-api"], environment(host, { demoMode: true }));
}

function printAddresses(host) {
  const urls = addresses(host);
  console.log("");
  console.log(`Dashboard:      ${urls.web}`);
  console.log(`Widget host:    ${urls.widget}`);
  console.log(`Demo websites:  ${urls.site}/techstore/ and ${urls.site}/hotelpro/ (links on the sign-in page)`);
  console.log("");
  console.log("Expose them with Tailscale Funnel (once; it remembers them):");
  console.log(`  tailscale funnel --bg --https=443 http://127.0.0.1:${PORTS.web}`);
  console.log(`  tailscale funnel --bg --https=8443 http://127.0.0.1:${PORTS.widget}`);
  console.log(`  tailscale funnel --bg --https=10000 http://127.0.0.1:${PORTS.site}`);
}

try {
  if (command === "start") {
    const host = tailscaleHost();
    if (!existsSync(CONFIG)) {
      reset(host);
    } else {
      compose(["up", "-d", "--build", "--wait"], environment(host, { demoMode: true }));
    }
    printAddresses(host);
  } else if (command === "reset") {
    const host = tailscaleHost();
    reset(host);
    printAddresses(host);
  } else if (command === "stop") {
    compose(["stop"]);
  } else if (command === "status") {
    compose(["ps"]);
    try {
      printAddresses(tailscaleHost());
    } catch (error) {
      console.log(error.message);
    }
  } else {
    throw new Error(`Unknown command "${command}". Use start, reset, stop or status.`);
  }
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
