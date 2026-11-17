import { beforeEach, describe, expect, it, vi } from "vitest";
import { ChatWidget, describeCitation, NOTICES, type ChatMessage } from "./chat";
import { reusableSession, mount, storageKey } from "./loader";
import { asLoaderMessage, asWidgetMessage, type FromWidget, type WidgetConfig } from "./protocol";
import { SseParser } from "./sse";

const CONFIG: WidgetConfig = {
  title: "TechStore",
  theme_color: "#112233",
  position: "BOTTOM_RIGHT",
  show_branding: true,
  welcome_message: "Hi! How can I help?",
};

function message(overrides: Partial<ChatMessage>): ChatMessage {
  return { id: "m1", sender_type: "CUSTOMER", body: "Hello", created_at: "2026-11-17T10:00:00Z", citations: [], ...overrides };
}

function sse(events: Array<[string, unknown]>): Response {
  const body = events.map(([name, data]) => `event:${name}\ndata:${JSON.stringify(data)}\n\n`).join("");
  return new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** A widget wired to a fake parent that grants sessions immediately. */
function setup(fetchImpl: (url: string, init: RequestInit) => Promise<Response>) {
  const root = document.createElement("div");
  document.body.replaceChildren(root);
  const posted: FromWidget[] = [];
  const calls: Array<{ url: string; init: RequestInit }> = [];
  let keys = 0;
  const widget: ChatWidget = new ChatWidget({
    root,
    fetch: ((url: string, init: RequestInit) => {
      calls.push({ url, init });
      return fetchImpl(url, init);
    }) as typeof fetch,
    postToParent: (m) => {
      posted.push(m);
      if (m.type === "session-request") {
        queueMicrotask(() =>
          widget.handleLoaderMessage({
            source: "servora-loader",
            type: "session",
            session: { token: `token-${m.renew ? "renewed" : "first"}`, conversation_id: "c1", expires_at: "2099-01-01T00:00:00Z" },
          }),
        );
      }
    },
    newKey: () => `key-${++keys}`,
  });
  widget.handleLoaderMessage({ source: "servora-loader", type: "init", config: CONFIG, apiBase: "http://api.test/" });
  const input = () => root.querySelector("textarea")!;
  return { root, widget, posted, calls, input };
}

const EMPTY = { conversation_id: "c1", status: "OPEN", control_mode: "AI_ACTIVE", messages: [] };

describe("chat widget", () => {
  beforeEach(() => {
    document.body.replaceChildren();
  });

  it("starts closed with an accessible launcher and opens a dialog with the welcome message", async () => {
    const { root, widget, posted } = setup(async () => json(200, EMPTY));
    const launcher = root.querySelector<HTMLButtonElement>(".launcher")!;

    expect(launcher.getAttribute("aria-label")).toBe("Open chat: TechStore");
    expect(root.querySelector(".panel")!.hasAttribute("hidden")).toBe(true);

    await widget.openPanel();

    expect(posted.map((m) => m.type)).toEqual(["open", "session-request"]);
    expect(root.querySelector<HTMLElement>(".panel")!.hidden).toBe(false);
    expect(root.querySelector('[role="dialog"]')!.getAttribute("aria-label")).toBe("TechStore");
    expect(root.querySelector('[role="log"]')!.textContent).toContain("Hi! How can I help?");
    expect(root.querySelector(".branding")!.textContent).toBe("Powered by Servora");
    expect(root.style.getPropertyValue("--servora-accent")).toBe("#112233");
  });

  it("sends a message with an idempotency key and shows the streamed answer with its sources", async () => {
    const reply = message({
      id: "m2",
      sender_type: "ASSISTANT",
      body: "Refunds are accepted within 30 days.",
      citations: [{ filename: "refunds.pdf", page_number: 2, heading: null, snippet: "Within 30 days." }],
    });
    const { root, widget, calls, input } = setup(async (url) =>
      url.endsWith("/messages")
        ? sse([["customer_message", message({ body: "Refunds?" })], ["assistant_typing", {}], ["assistant_message", reply], ["done", {}]])
        : json(200, EMPTY),
    );
    await widget.openPanel();
    input().value = "  Refunds?  ";

    await widget.send();

    const post = calls.find((c) => c.url.endsWith("/conversation/messages"))!;
    expect(post.url).toBe("http://api.test/api/v1/public/conversation/messages");
    const headers = new Headers(post.init.headers);
    expect(headers.get("Authorization")).toBe("Bearer token-first");
    expect(headers.get("Idempotency-Key")).toBe("key-1");
    expect(JSON.parse(post.init.body as string)).toEqual({ text: "Refunds?" });
    expect(post.init.credentials).toBe("omit");

    const log = root.querySelector('[role="log"]')!;
    expect(log.textContent).toContain("Refunds?");
    expect(log.textContent).toContain("Refunds are accepted within 30 days.");
    expect(log.querySelector(".sources li")!.textContent).toBe("refunds.pdf, page 2");
    expect(input().value).toBe("");
  });

  it("renders customer and model text as text, never as HTML", async () => {
    const hostile = '<img src=x onerror="alert(1)"><script>alert(2)</script>';
    const { root, widget, input } = setup(async (url) =>
      url.endsWith("/messages")
        ? sse([
            ["customer_message", message({ body: hostile })],
            ["assistant_message", message({ id: "m2", sender_type: "ASSISTANT", body: hostile, citations: [{ filename: hostile, page_number: null, heading: null, snippet: "" }] })],
          ])
        : json(200, EMPTY),
    );
    await widget.openPanel();
    input().value = hostile;

    await widget.send();

    expect(root.querySelector("img")).toBeNull();
    expect(root.querySelector("script")).toBeNull();
    expect(root.querySelector('[role="log"]')!.textContent).toContain(hostile);
  });

  it("explains a handoff, hides the button and shows notices instead of a reply", async () => {
    const { root, widget, input } = setup(async (url) =>
      url.endsWith("/messages")
        ? sse([["customer_message", message({})], ["notice", { code: "HUMAN_PENDING" }], ["done", {}]])
        : json(200, EMPTY),
    );
    await widget.openPanel();
    input().value = "Hello";

    await widget.send();

    expect(root.querySelector('[role="status"]')!.textContent).toBe(NOTICES.HUMAN_PENDING);
    expect(root.querySelector<HTMLButtonElement>(".human")!.hidden).toBe(true);
  });

  it("asks for a person and shows the conversation the server returns", async () => {
    const handedOff = {
      ...EMPTY,
      control_mode: "HUMAN_PENDING",
      messages: [message({ id: "s1", sender_type: "SYSTEM", body: "A member of our team will reply." })],
    };
    const { root, widget, calls } = setup(async (url) => (url.endsWith("/handoff") ? json(200, handedOff) : json(200, EMPTY)));
    await widget.openPanel();

    await widget.requestHuman();

    expect(calls.at(-1)!.init.method).toBe("POST");
    expect(root.querySelector(".message.system")!.textContent).toContain("A member of our team will reply.");
    expect(root.querySelector<HTMLButtonElement>(".human")!.hidden).toBe(true);
  });

  it("starts a new session when the old one has expired and retries the same message", async () => {
    let attempts = 0;
    const { widget, input, posted, calls } = setup(async (url) => {
      if (!url.endsWith("/messages")) {
        return json(200, EMPTY);
      }
      attempts += 1;
      return attempts === 1 ? json(401, { error: { code: "WIDGET_SESSION_INVALID" } }) : sse([["customer_message", message({})]]);
    });
    await widget.openPanel();
    input().value = "Hello";

    await widget.send();

    expect(posted.filter((m) => m.type === "session-request").map((m) => (m as { renew: boolean }).renew)).toEqual([false, true]);
    const posts = calls.filter((c) => c.url.endsWith("/messages"));
    expect(new Headers(posts[1]!.init.headers).get("Authorization")).toBe("Bearer token-renewed");
    expect(new Headers(posts[1]!.init.headers).get("Idempotency-Key")).toBe("key-1");
    expect(widget.state.status?.text).toContain("new chat has started");
  });

  it("keeps the typed text and explains rate limits and offline errors", async () => {
    let mode: "limited" | "offline" = "limited";
    const { widget, input } = setup(async (url) => {
      if (!url.endsWith("/messages")) {
        return json(200, EMPTY);
      }
      if (mode === "offline") {
        throw new TypeError("Failed to fetch");
      }
      return json(429, { error: { code: "WIDGET_RATE_LIMITED", message: "Too many" } });
    });
    await widget.openPanel();
    input().value = "Hello";

    await widget.send();
    expect(widget.state.status).toEqual({ text: "You are sending messages very quickly. Please wait a moment.", tone: "error" });
    expect(input().value).toBe("Hello");

    mode = "offline";
    await widget.send();
    expect(widget.state.status?.tone).toBe("error");
    expect(input().value).toBe("Hello");
  });

  it("closes with Escape and returns focus to the launcher", async () => {
    const { root, widget, posted } = setup(async () => json(200, EMPTY));
    await widget.openPanel();

    root.querySelector(".panel")!.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));

    expect(widget.state.open).toBe(false);
    expect(posted.at(-1)!.type).toBe("close");
    expect(document.activeElement).toBe(root.querySelector(".launcher"));
  });

  it("describes citations", () => {
    expect(describeCitation({ filename: "a.pdf", page_number: 3, heading: "Returns", snippet: "" })).toBe("a.pdf, page 3, Returns");
    expect(describeCitation({ filename: "b.md", page_number: null, heading: null, snippet: "" })).toBe("b.md");
  });
});

describe("protocol", () => {
  it("accepts only well-formed messages from the expected side", () => {
    expect(asWidgetMessage({ source: "servora-widget", type: "open" })).toEqual({ source: "servora-widget", type: "open" });
    expect(asWidgetMessage({ source: "servora-widget", type: "session-request", renew: "yes" })).toEqual({
      source: "servora-widget",
      type: "session-request",
      renew: false,
    });
    expect(asWidgetMessage({ source: "servora-loader", type: "open" })).toBeNull();
    expect(asWidgetMessage("open")).toBeNull();
    expect(asLoaderMessage({ source: "servora-loader", type: "session", session: { token: 1 } })).toBeNull();
    const init = asLoaderMessage({
      source: "servora-loader",
      type: "init",
      apiBase: "http://api.test",
      config: { ...CONFIG, theme_color: "red; background:url(x)" },
    });
    expect(init && init.type === "init" && init.config.theme_color).toBe("#4f46e5");
  });
});

describe("SSE parser", () => {
  it("handles events split across chunks, CRLF line endings, comments and multi-line data", () => {
    const parser = new SseParser();
    expect(parser.push("event: customer_message\r\nda")).toEqual([]);
    expect(parser.push('ta: {"a":1}\r\n\r\n: keep-alive\n\nevent:done\ndata:{}\n')).toEqual([
      { event: "customer_message", data: '{"a":1}' },
    ]);
    expect(parser.push("\nevent:x\ndata:line1\ndata:line2\n\n")).toEqual([
      { event: "done", data: "{}" },
      { event: "x", data: "line1\nline2" },
    ]);
  });
});

describe("loader", () => {
  it("reuses only stored sessions that are not about to expire", () => {
    const now = Date.parse("2026-11-17T10:00:00Z");
    const fresh = JSON.stringify({ token: "t", conversation_id: "c", expires_at: "2026-11-17T11:00:00Z" });
    const stale = JSON.stringify({ token: "t", conversation_id: "c", expires_at: "2026-11-17T10:00:30Z" });
    expect(reusableSession(fresh, now)?.token).toBe("t");
    expect(reusableSession(stale, now)).toBeNull();
    expect(reusableSession("{not json", now)).toBeNull();
    expect(reusableSession(null, now)).toBeNull();
    expect(storageKey("wk_abc")).toBe("servora:session:wk_abc");
  });

  it("shows nothing when the widget is refused, and mounts a fixed iframe from the widget origin otherwise", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const refused = await mount({
      publicKey: "wk_abc",
      widgetOrigin: "http://widget.test",
      apiBase: "http://api.test",
      fetch: async () => json(403, { error: { code: "ORIGIN_NOT_ALLOWED" } }),
      storage: null,
    });
    expect(refused).toBeNull();
    expect(warn).toHaveBeenCalled();
    expect(document.querySelector("iframe")).toBeNull();

    const requested: string[] = [];
    const iframe = await mount({
      publicKey: "wk_abc",
      widgetOrigin: "http://widget.test",
      apiBase: "http://api.test/",
      fetch: async (url) => {
        requested.push(String(url));
        return json(200, { ...CONFIG, position: "BOTTOM_LEFT" });
      },
      storage: null,
    });
    expect(requested).toEqual(["http://api.test/api/v1/public/widgets/wk_abc/config"]);
    expect(iframe!.src).toBe("http://widget.test/widget.html");
    expect(iframe!.style.position).toBe("fixed");
    expect(iframe!.style.left).toBe("0px");
    expect(iframe!.title).toBe("TechStore");
  });
});
