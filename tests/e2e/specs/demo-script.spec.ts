import AxeBuilder from "@axe-core/playwright";
import { expect, test, type FrameLocator, type Page } from "@playwright/test";
import { join } from "node:path";
import {
  API,
  type Actor,
  ask,
  createWorkspace,
  demoUrl,
  FIXTURES,
  mailLink,
  newActor,
  openChat,
  registerAndSignIn,
  widgetToken,
} from "../support/stack";

/**
 * The MVP demonstration script, step by step, in a real browser. Steps run in
 * order and share state. Step 11, the cross-tenant test output, is the core API suite; the browser
 * part of it is checked here as step 10b.
 */
test.describe.serial("MVP demonstration script", () => {
  const TECHSTORE_SITE = "http://localhost:5181";
  const HOTELPRO_SITE = "http://localhost:5182";
  const NOTE = "VIP customer, check order 4471 by hand";

  let ownerA: Actor;
  let ownerB: Actor;
  let agentA: Actor;
  let agentB: Actor;
  let techStore: string;
  let hotelPro: string;
  let keyA: string;
  let keyB: string;
  let customer: Page;
  let chat: FrameLocator;

  test.beforeAll(async ({ browser }) => {
    ownerA = await newActor(browser, "TechStore Owner");
    ownerB = await newActor(browser, "HotelPro Owner");
    agentA = await newActor(browser, "TechStore Agent");
    agentB = await newActor(browser, "HotelPro Agent");
    customer = await (await browser.newContext()).newPage();
  });

  test("1. register TechStore and HotelPro as separate tenants", async () => {
    await registerAndSignIn(ownerA);
    techStore = await createWorkspace(ownerA, "TechStore");
    await registerAndSignIn(ownerB);
    hotelPro = await createWorkspace(ownerB, "HotelPro");
    expect(techStore).not.toEqual(hotelPro);
  });

  test("2. invite one agent to each workspace", async () => {
    for (const [owner, agent, workspace] of [
      [ownerA, agentA, techStore],
      [ownerB, agentB, hotelPro],
    ] as const) {
      const invitation = await mailLink("invitation", async () => {
        await owner.page.goto(`/w/${workspace}/team`);
        await owner.page.getByLabel("Email address").fill(agent.email);
        await owner.page.getByLabel("Role").selectOption("AGENT");
        await owner.page.getByRole("button", { name: "Send invitation" }).click();
        await expect(owner.page.getByText(agent.email, { exact: true })).toBeVisible();
      });
      await registerAndSignIn(agent);
      await agent.page.goto(invitation);
      await agent.page.getByRole("button", { name: "Accept invitation" }).click();
      await agent.page.waitForURL(new RegExp(`/w/${workspace}`));
    }
  });

  test("3-4. upload different documents to each tenant and wait until both are ready", async () => {
    for (const [owner, workspace, file] of [
      [ownerA, techStore, "techstore-returns.md"],
      [ownerB, hotelPro, "hotelpro-stay.md"],
    ] as const) {
      await owner.page.goto(`/w/${workspace}/knowledge`);
      await owner.page.getByLabel("Choose a document to upload").setInputFiles(join(FIXTURES, file));
      const row = owner.page.getByRole("listitem").filter({ hasText: file });
      await expect(row).toBeVisible();
      // Extraction, chunking and embedding run in the worker.
      await expect(async () => {
        await owner.page.reload();
        await expect(row.getByText("Ready")).toBeVisible({ timeout: 2_000 });
      }).toPass({ timeout: 150_000, intervals: [2_000] });
    }
  });

  test("5. embed each widget on a separate demo page", async () => {
    const publish = async (owner: Actor, workspace: string, site: string) => {
      await owner.page.goto(`/w/${workspace}/widget`);
      await owner.page.getByLabel("Websites allowed to show this widget").fill(site);
      await owner.page.getByRole("button", { name: "Save allowed websites" }).click();
      await expect(owner.page.getByText("Allowed websites saved.")).toBeVisible();
      return (await owner.page.locator("p", { hasText: "Widget key:" }).locator("code").textContent())!.trim();
    };
    keyA = await publish(ownerA, techStore, TECHSTORE_SITE);
    keyB = await publish(ownerB, hotelPro, HOTELPRO_SITE);
    expect(keyA).not.toEqual(keyB);

    // A widget key only works on its own website.
    await customer.goto(demoUrl(HOTELPRO_SITE, keyA));
    await expect(customer.frameLocator("iframe").getByRole("button", { name: /Open chat/ })).toHaveCount(0);
  });

  test("6. each widget answers only from its own documents", async () => {
    const hotelChat = await openChat(customer, HOTELPRO_SITE, keyB);
    await ask(hotelChat, "What time is check-in?");
    await expect(hotelChat.getByRole("log")).toContainText("3 pm", { timeout: 60_000 });
    await expect(hotelChat.getByRole("log")).toContainText("hotelpro-stay.md");

    chat = await openChat(customer, TECHSTORE_SITE, keyA);
    await ask(chat, "How many days do I have to return an item?");
    await expect(chat.getByRole("log")).toContainText("30 days", { timeout: 60_000 });
    await expect(chat.getByRole("log")).toContainText("techstore-returns.md");
  });

  test("7. an unsupported question is not answered from another tenant's documents", async () => {
    // Real messages only: the "assistant is typing" placeholder is also a .message.
    const messages = chat.locator(".message:not(.typing)");
    const before = await messages.count();
    await ask(chat, "What time is check-in at the hotel?");
    await expect(messages).toHaveCount(before + 2, { timeout: 60_000 });
    const reply = messages.last();
    await expect(reply).toHaveClass(/assistant/);
    await expect(reply).toContainText(/\S/);
    await expect(reply).not.toContainText("3 pm");
    await expect(reply).not.toContainText("hotelpro-stay.md");
    await expect(reply.locator(".sources-title")).toHaveCount(0);
  });

  test("8. escalate, assign an agent and take over", async () => {
    await chat.getByRole("button", { name: "Talk to a person" }).click();
    await expect(chat.getByRole("log")).toContainText(/person|team/i);

    await ownerA.page.goto(`/w/${techStore}/inbox`);
    await ownerA.page.getByRole("region", { name: "Ticket queue" }).getByRole("link").first().click();
    await ownerA.page.getByLabel("Assign to").selectOption({ label: agentA.name });
    await expect(ownerA.page.getByRole("region", { name: "Ticket", exact: true })).toContainText(agentA.name);

    await agentA.page.goto(`/w/${techStore}/inbox`);
    await agentA.page.getByRole("region", { name: "Ticket queue" }).getByRole("link").first().click();
    await agentA.page.getByRole("button", { name: "Take over" }).click();
    await expect(agentA.page.getByLabel("Reply to the customer")).toBeEnabled();
  });

  test("9. after the takeover no AI reply reaches the customer, and the agent's reply does", async () => {
    // The parallel race itself is exercised deterministically by the core API suite
    // (anAiReplyIsNeverDeliveredAfterATakeover, 24 rounds per run). Here: the customer writes after
    // the takeover and only the person answers.
    const assistantReplies = chat.locator(".message.assistant");
    const before = await assistantReplies.count();
    await ask(chat, "Is anyone there?");
    await expect(chat.getByRole("log")).toContainText("Is anyone there?");
    await customer.waitForTimeout(5_000);
    await expect(assistantReplies).toHaveCount(before);

    await agentA.page.getByLabel("Reply to the customer").fill("Hi, this is the TechStore team. How can I help?");
    await agentA.page.getByRole("button", { name: "Send reply" }).click();
    // The widget checks for team replies every five seconds.
    await expect(chat.getByRole("log")).toContainText("this is the TechStore team", { timeout: 20_000 });
    await expect(assistantReplies).toHaveCount(before);
  });

  test("10. an internal note is never visible to the customer", async () => {
    await agentA.page.getByRole("tab", { name: "Internal note" }).click();
    await agentA.page.getByLabel("Internal note, not shown to the customer").fill(NOTE);
    await agentA.page.getByRole("button", { name: "Add note" }).click();
    await expect(agentA.page.getByRole("list", { name: "Messages and notes" })).toContainText(NOTE);

    // Wait past one polling cycle of the widget, then check both the screen and the public API.
    await customer.waitForTimeout(6_000);
    await expect(chat.getByRole("log")).not.toContainText(NOTE);
    const token = await widgetToken(customer, keyA);
    const history = await customer.request.get(`${API}/api/v1/public/conversation`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(history.ok()).toBe(true);
    const body = await history.text();
    expect(body).toContain("this is the TechStore team");
    expect(body).not.toContain(NOTE);
  });

  test("10b. neither workspace can reach the other's data in the dashboard", async () => {
    await ownerA.page.goto(`/w/${hotelPro}/inbox`);
    await expect(ownerA.page.getByRole("alert")).toContainText("not a member");
    await agentB.page.goto(`/w/${hotelPro}/inbox`);
    await expect(agentB.page.getByText("No tickets here.")).toBeVisible();
    await agentB.page.goto(`/w/${techStore}/inbox`);
    await expect(agentB.page.getByRole("alert")).toContainText("not a member");
  });

  test("M6 pages show this workspace's activity", async () => {
    await ownerA.page.goto(`/w/${techStore}/overview`);
    await expect(ownerA.page.getByText("Escalated to a person")).toBeVisible();
    await expect(ownerA.page.locator("div", { hasText: /^Conversations\d+$/ }).first()).toBeVisible();

    await ownerA.page.goto(`/w/${techStore}/usage`);
    const aiRequests = ownerA.page.getByRole("meter", { name: "AI requests" });
    await expect(aiRequests).toBeVisible();
    expect(Number(await aiRequests.getAttribute("aria-valuenow"))).toBeGreaterThan(0);

    await ownerA.page.goto(`/w/${techStore}/audit`);
    await expect(ownerA.page.getByRole("cell", { name: "Ticket assigned" })).toBeVisible();
    await expect(ownerA.page.getByRole("cell", { name: "Conversation taken over" })).toBeVisible();

    // Agents see neither page in the navigation.
    await agentA.page.goto(`/w/${techStore}/inbox`);
    const nav = agentA.page.getByRole("navigation", { name: "Workspace" });
    await expect(nav.getByRole("link", { name: "Overview" })).toHaveCount(0);
    await expect(nav.getByRole("link", { name: /Usage/ })).toHaveCount(0);
  });

  test("accessibility: no serious or critical WCAG 2.1 AA violations in the dashboard and widget", async ({ browser }) => {
    /** Runs axe on the current page, including the widget's iframe, and returns serious and critical findings. */
    const audit = async (page: Page, label: string): Promise<string[]> => {
      const results = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
        .analyze();
      return results.violations
        .filter((violation) => violation.impact === "serious" || violation.impact === "critical")
        .map(
          (violation) =>
            `${label}: ${violation.id} (${violation.impact}) on ${violation.nodes
              .slice(0, 3)
              .map((node) => node.target.join(" >> "))
              .join(", ")}${violation.nodes.length > 3 ? ` and ${violation.nodes.length - 3} more` : ""}`,
        );
    };

    const findings: string[] = [];
    const visitor = await (await browser.newContext()).newPage();
    for (const path of ["/login", "/register"]) {
      await visitor.goto(path);
      await expect(visitor.getByRole("heading").first()).toBeVisible();
      findings.push(...(await audit(visitor, path)));
    }

    const pages = ["overview", "inbox", "conversations", "knowledge", "assistant", "playground", "widget", "team", "usage", "audit", "settings"];
    for (const name of pages) {
      await ownerA.page.goto(`/w/${techStore}/${name}`);
      await expect(ownerA.page.getByRole("main")).toBeVisible();
      await ownerA.page.waitForLoadState("networkidle");
      findings.push(...(await audit(ownerA.page, name)));
    }

    // The same pages in the dark theme, which follows the system setting.
    await ownerA.page.emulateMedia({ colorScheme: "dark" });
    for (const name of pages) {
      await ownerA.page.goto(`/w/${techStore}/${name}`);
      await expect(ownerA.page.locator("html.dark")).toHaveCount(1);
      await expect(ownerA.page.getByRole("main")).toBeVisible();
      await ownerA.page.waitForLoadState("networkidle");
      findings.push(...(await audit(ownerA.page, `${name} (dark)`)));
    }
    await ownerA.page.emulateMedia({ colorScheme: "light" });

    // The customer's chat, open on the demo website, with the conversation from the earlier steps.
    await customer.bringToFront();
    findings.push(...(await audit(customer, "widget chat")));

    expect(findings, findings.join("\n")).toEqual([]);
  });
});
