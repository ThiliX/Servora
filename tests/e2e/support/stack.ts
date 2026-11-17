import { expect, type Browser, type BrowserContext, type FrameLocator, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

/**
 * "dev": the API, dashboard and widget run on the host, started by Playwright.
 * "prod": the production Compose profile (compose.prod.yml) is already running; the dashboard and
 * API are behind the gateway on 8000 and the widget is served on 8001.
 */
export const TARGET = process.env.E2E_TARGET === "prod" ? "prod" : "dev";
export const API = TARGET === "prod" ? "http://localhost:8000" : "http://localhost:8080";
/** Where the demo websites load the widget from. */
const WIDGET_PARAMS =
  // As the dashboard's embed snippet does: the built loader, with the API reached through the widget host.
  TARGET === "prod" ? "&widget=http://localhost:8001&loader=/servora-widget.js&api=http://localhost:8001" : "";
/** In the prod target, mail links are read from the core API container's log. */
const COMPOSE_PROJECT = process.env.E2E_COMPOSE_PROJECT ?? "servora-prod";
const COMPOSE_FILES = ["-f", "../../docker-compose.yml", "-f", "../../compose.prod.yml"];
export const PASSWORD = "e2e-correct-horse-battery";
export const LOG_FILE = process.env.E2E_API_LOG ?? join(here, "..", ".artifacts", "api.log");
export const FIXTURES = join(here, "..", "fixtures");

/** A suffix that makes this run's emails and workspace addresses unique. */
export const RUN = Date.now().toString(36);

export interface Actor {
  name: string;
  email: string;
  context: BrowserContext;
  page: Page;
}

function readLog(): string {
  if (TARGET === "prod") {
    return execFileSync("docker", ["compose", "-p", COMPOSE_PROJECT, ...COMPOSE_FILES, "logs", "--no-color", "core-api"], {
      encoding: "utf8",
      maxBuffer: 64 * 1024 * 1024,
    });
  }
  return existsSync(LOG_FILE) ? readFileSync(LOG_FILE, "utf8") : "";
}

/**
 * Runs an action that sends a development mail and returns the link the API logged for it. Only
 * lines written after the action started are considered, and the steps run one at a time.
 */
export async function mailLink(kind: "verification" | "invitation", action: () => Promise<void>): Promise<string> {
  const start = readLog().length;
  await action();
  const pattern = new RegExp(`DEV MAIL ${kind} link: (\\S+)`);
  let link: string | undefined;
  await expect
    .poll(
      () => {
        const added = readLog().slice(start);
        link = pattern.exec(added)?.[1];
        return link;
      },
      { message: `waiting for a ${kind} link in ${LOG_FILE}`, timeout: 15_000 },
    )
    .toBeTruthy();
  return link!;
}

export async function newActor(browser: Browser, name: string): Promise<Actor> {
  const context = await browser.newContext();
  const page = await context.newPage();
  return { name, email: `e2e-${name.toLowerCase().replace(/\W+/g, "-")}-${RUN}@example.test`, context, page };
}

/** Registers through the dashboard, opens the verification link, and signs in. */
export async function registerAndSignIn(actor: Actor): Promise<void> {
  const { page } = actor;
  const link = await mailLink("verification", async () => {
    await page.goto("/register");
    await page.getByLabel("Your name").fill(actor.name);
    await page.getByLabel("Work email").fill(actor.email);
    await page.getByLabel("Password").fill(PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByRole("status")).toBeVisible();
  });
  await page.goto(link);
  await expect(page.getByText("Your email is verified")).toBeVisible();
  await page.goto("/login");
  await page.getByLabel("Email").fill(actor.email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Your workspaces" })).toBeVisible();
}

/** Creates a workspace and returns its id. */
export async function createWorkspace(owner: Actor, name: string): Promise<string> {
  const { page } = owner;
  await page.goto("/");
  await page.getByLabel("Business name").fill(name);
  await page.getByLabel("Workspace address").fill(`${name.toLowerCase()}-${RUN}`);
  await page.getByRole("button", { name: "Create workspace" }).click();
  await page.waitForURL(/\/w\/[0-9a-f-]{36}\//);
  return /\/w\/([0-9a-f-]{36})\//.exec(page.url())![1]!;
}

/** The chat inside a demo website: opens it and returns the iframe. */
export async function openChat(page: Page, site: string, widgetKey: string): Promise<FrameLocator> {
  await page.goto(`${site}/?key=${widgetKey}${WIDGET_PARAMS}`);
  const chat = page.frameLocator("iframe");
  await chat.getByRole("button", { name: /Open chat/ }).click();
  return chat;
}

export async function ask(chat: FrameLocator, question: string): Promise<void> {
  await chat.getByLabel("Your message").fill(question);
  await chat.getByRole("button", { name: "Send" }).click();
}

/** The widget's session token, as the loader stored it for the demo page. */
export async function widgetToken(page: Page, widgetKey: string): Promise<string> {
  const stored = await page.evaluate((key) => sessionStorage.getItem(`servora:session:${key}`), widgetKey);
  expect(stored, "the loader stores the session for this page").toBeTruthy();
  return (JSON.parse(stored!) as { token: string }).token;
}

/** The demo website's address for a widget key, on this target. */
export function demoUrl(site: string, widgetKey: string): string {
  return `${site}/?key=${widgetKey}${WIDGET_PARAMS}`;
}
