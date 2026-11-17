// Starts the core API for the browser tests, with the repository's .env, development mail links
// written to the log, and the log written to a file the tests read. Run by Playwright's webServer.
import { spawn } from "node:child_process";
import { mkdirSync, readFileSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..", "..");
const logFile = join(here, "..", ".artifacts", "api.log");

const env = { ...process.env };
for (const line of readFileSync(join(root, ".env"), "utf8").split(/\r?\n/)) {
  const match = /^([A-Z0-9_]+)=(.*)$/.exec(line.trim());
  if (match && env[match[1]] === undefined) {
    env[match[1]] = match[2];
  }
}
// Development only: verification and invitation links go to the log instead of email.
env.SERVORA_MAIL_LOG_LINKS = "true";
env.LOGGING_FILE_NAME = logFile;
// Every run signs up four users from this machine; the production limit is 10 an hour per address.
env.SERVORA_AUTH_REGISTRATIONSPERADDRESSPERHOUR = "1000";
mkdirSync(dirname(logFile), { recursive: true });
rmSync(logFile, { force: true });

const apiDir = join(root, "services", "core-api");
const windows = process.platform === "win32";
const [command, args] = windows
  ? ["cmd.exe", ["/d", "/c", join(apiDir, "mvnw.cmd"), "-q", "spring-boot:run"]]
  : [join(apiDir, "mvnw"), ["-q", "spring-boot:run"]];
const child = spawn(command, args, { cwd: apiDir, env, stdio: "inherit" });
const stop = () => child.kill();
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
child.on("exit", (code) => process.exit(code ?? 0));
