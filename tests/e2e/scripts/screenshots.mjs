#!/usr/bin/env node
// Captures the README screenshots from a running production profile seeded by scripts/seed-demo.mjs:
//
//   node tests/e2e/scripts/screenshots.mjs --seed .demo/seed-<run>.json [--jaeger http://localhost:16686]
//
// It drives the real app: a customer chats on each demo website, asks for a person, and the TechStore
// owner takes over in the inbox. Images go to docs/images/. Only demo data appears in them; the
// widget page (which shows the widget key) is not captured.
import { chromium } from "@playwright/test";
import { spawn } from "node:child_process";
import { mkdirSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const out = join(root, "docs", "images");
const { values: options } = parseArgs({
  options: { seed: { type: "string" }, jaeger: { type: "string" } },
});
if (!options.seed) throw new Error("Pass --seed .demo/seed-<run>.json");
const seed = JSON.parse(readFileSync(options.seed, "utf8"));
const [techStore, hotelPro] = seed.workspaces;
const DASHBOARD = seed.api;

mkdirSync(out, { recursive: true });
const demoSites = spawn(process.execPath, [join(root, "apps", "widget", "demo", "serve.mjs")], { stdio: "ignore" });
await new Promise((done) => setTimeout(done, 1_000));

const browser = await chromium.launch();
const viewport = { width: 1280, height: 800 };
const shot = async (page, name) => {
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: join(out, `${name}.png`) });
  console.log(`docs/images/${name}.png`);
};

/** Opens the chat on a demo website and waits for the welcome message. */
async function openChat(page, workspace) {
  await page.goto(workspace.demo_page);
  const chat = page.frameLocator("iframe");
  await chat.getByRole("button", { name: /Open chat/ }).click();
  await chat.getByRole("log").waitFor();
  return chat;
}

/** Sends a message and waits until the assistant's reply has arrived. */
async function ask(chat, question) {
  const replies = chat.locator(".message:not(.typing)");
  const before = await replies.count();
  await chat.getByLabel("Your message").fill(question);
  await chat.getByRole("button", { name: "Send" }).click();
  await chat.locator(".message:not(.typing)").nth(before + 1).waitFor({ timeout: 90_000 });
}

async function signIn(person) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  await page.goto(`${DASHBOARD}/login`);
  await page.getByLabel("Email").fill(person.email);
  await page.getByLabel("Password").fill(person.password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByRole("heading", { name: "Your workspaces" }).waitFor();
  return page;
}

try {
  // 1. A customer on the TechStore website gets a cited answer.
  const customer = await (await browser.newContext({ viewport })).newPage();
  const chat = await openChat(customer, techStore);
  await ask(chat, "How long do I have to send something back, and when do I get my money?");
  await shot(customer, "widget-answer");

  // 2. The same question on HotelPro's website is answered only from HotelPro's documents.
  const guest = await (await browser.newContext({ viewport })).newPage();
  const hotelChat = await openChat(guest, hotelPro);
  await ask(hotelChat, "Can I bring my dog?");
  await shot(guest, "widget-other-tenant");

  // 3. The TechStore customer asks for a person: a ticket is opened.
  await chat.getByRole("button", { name: "Talk to a person" }).click();
  await chat.getByRole("log").getByText(/person|team/i).last().waitFor();

  // 4. The owner takes over in the inbox, adds a note and replies.
  const owner = await signIn(techStore.owner);
  await owner.goto(`${DASHBOARD}/w/${techStore.workspace_id}/inbox`);
  await owner.getByRole("region", { name: "Ticket queue" }).getByRole("link").first().click();
  await owner.getByRole("button", { name: "Take over" }).click();
  const reply = owner.getByLabel("Reply to the customer");
  await owner.waitForFunction(() => !document.querySelector("#inbox-composer")?.hasAttribute("disabled"));
  await reply.fill("Hi, this is Sam from TechStore. I can help with your return.");
  await owner.getByRole("button", { name: "Send reply" }).click();
  const thread = owner.getByRole("list", { name: "Messages and notes" });
  await thread.getByText("this is Sam").waitFor();
  await owner.getByRole("tab", { name: "Internal note" }).click();
  await owner.getByLabel("Internal note, not shown to the customer").fill("Repeat customer. Offer a free return label.");
  await owner.getByRole("button", { name: "Add note" }).click();
  await thread.getByText("Repeat customer").waitFor();
  await shot(owner, "inbox-takeover");
  // The same view in the dark theme, which follows the system setting.
  await owner.emulateMedia({ colorScheme: "dark" });
  await owner.reload();
  await owner.getByRole("list", { name: "Messages and notes" }).getByText("Repeat customer").waitFor();
  await shot(owner, "inbox-dark");
  await owner.emulateMedia({ colorScheme: "light" });
  await owner.reload();

  // The customer sees the person's reply, and never the note.
  await chat.getByRole("log").getByText("this is Sam").waitFor({ timeout: 20_000 });
  await shot(customer, "widget-human-reply");

  // 5. The playground shows why the assistant answered: sources and retrieval scores.
  let traceId = null;
  owner.on("response", (response) => {
    if (response.url().endsWith("/assistant/test")) traceId = response.headers()["x-request-id"] ?? traceId;
  });
  await owner.goto(`${DASHBOARD}/w/${techStore.workspace_id}/playground`);
  await owner.getByLabel("Ask a question as a customer would").fill("Is accidental damage covered by the warranty?");
  await owner.getByRole("button", { name: "Ask" }).click();
  await owner.getByRole("button", { name: "Ask" }).waitFor({ timeout: 90_000 });
  await owner.waitForLoadState("networkidle");
  await shot(owner, "playground");

  // 6. The knowledge base.
  await owner.goto(`${DASHBOARD}/w/${techStore.workspace_id}/knowledge`);
  await owner.getByText("warranty-and-repairs.md").waitFor();
  await shot(owner, "knowledge");

  // 7. The trace of the playground question, across the API, the AI service and the model call.
  if (options.jaeger && traceId) {
    const jaeger = await (await browser.newContext({ viewport: { width: 1440, height: 1000 } })).newPage();
    await new Promise((done) => setTimeout(done, 8_000)); // spans are exported in batches
    await jaeger.goto(`${options.jaeger.replace(/\/$/, "")}/trace/${traceId}`);
    await jaeger.getByText("servora-ai-service").first().waitFor({ timeout: 30_000 });
    await jaeger.waitForTimeout(1_000);
    await shot(jaeger, "trace");
  }
} finally {
  await browser.close();
  demoSites.kill();
}
