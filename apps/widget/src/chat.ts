import type { FromLoader, FromWidget, SessionGrant, WidgetConfig } from "./protocol";
import { readEvents } from "./sse";

export interface Citation {
  filename: string;
  page_number: number | null;
  heading: string | null;
  snippet: string;
}

export interface ChatMessage {
  id: string;
  sender_type: "CUSTOMER" | "ASSISTANT" | "AGENT" | "SYSTEM";
  body: string;
  created_at: string;
  citations: Citation[];
}

interface ConversationView {
  conversation_id: string;
  status: string;
  control_mode: string;
  feedback_submitted: boolean;
  messages: ChatMessage[];
}

export interface ChatDeps {
  root: HTMLElement;
  fetch: typeof fetch;
  postToParent: (message: FromWidget) => void;
  newKey: () => string;
  /** How often to check for replies from the team while a person is handling the chat. */
  pollMs?: number;
}

const MAX_LENGTH = 2000;

export const NOTICES: Record<string, string> = {
  HUMAN_PENDING: "A member of our team will reply here.",
  AUTOMATION_OFF: "A member of our team will reply here.",
  SUPPRESSED: "A member of our team has joined the conversation and will reply.",
  REPLY_PENDING: "Your message was received. The reply will appear here shortly.",
};

/** Describes where a cited passage came from, in words a customer understands. */
export function describeCitation(citation: Citation): string {
  const parts = [citation.filename];
  if (citation.page_number !== null) {
    parts.push(`page ${citation.page_number}`);
  }
  if (citation.heading) {
    parts.push(citation.heading);
  }
  return parts.join(", ");
}

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attributes: Record<string, string> = {},
  text?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    node.setAttribute(name, value);
  }
  if (text !== undefined) {
    // Always text, never markup: customer and model text is untrusted.
    node.textContent = text;
  }
  return node;
}

const SVG_NS = "http://www.w3.org/2000/svg";

/** Outline icons (Lucide, ISC licence): fixed path data only. */
const ICONS = {
  chat: ["M7.9 20A9 9 0 1 0 4 16.1L2 22Z"],
  close: ["M18 6 6 18", "m6 6 12 12"],
  send: [
    "M3.714 3.048a.498.498 0 0 0-.683.627l2.843 7.627a2 2 0 0 1 0 1.396l-2.842 7.627a.498.498 0 0 0 .682.627l18-8.5a.5.5 0 0 0 0-.904z",
    "M6 12h16",
  ],
  person: ["M18 20a6 6 0 0 0-12 0", "M12 14a4 4 0 1 0 0-8 4 4 0 0 0 0 8"],
  document: ["M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4"],
} as const;

function icon(name: keyof typeof ICONS, size = 18): SVGSVGElement {
  const svg = document.createElementNS(SVG_NS, "svg");
  for (const [key, value] of Object.entries({
    viewBox: "0 0 24 24",
    width: String(size),
    height: String(size),
    fill: "none",
    stroke: "currentColor",
    "stroke-width": "2",
    "stroke-linecap": "round",
    "stroke-linejoin": "round",
    "aria-hidden": "true",
    focusable: "false",
  })) {
    svg.setAttribute(key, value);
  }
  for (const d of ICONS[name]) {
    const path = document.createElementNS(SVG_NS, "path");
    path.setAttribute("d", d);
    svg.append(path);
  }
  return svg;
}

/**
 * The chat UI inside the iframe. All text from the customer, the model or the server is inserted
 * with textContent, so nothing it contains is ever interpreted as HTML.
 */
export class ChatWidget {
  private readonly deps: ChatDeps;
  private config: WidgetConfig | null = null;
  private apiBase = "";
  private session: SessionGrant | null = null;
  private sessionWaiters: Array<(session: SessionGrant | null) => void> = [];
  private messages: ChatMessage[] = [];
  private controlMode = "AI_ACTIVE";
  private conversationStatus = "OPEN";
  private feedbackSubmitted = false;
  private rating = 0;
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private open = false;
  private sending = false;
  private typing = false;
  private status: { text: string; tone: "info" | "error" } | null = null;

  private launcher!: HTMLButtonElement;
  private panel!: HTMLElement;
  private log!: HTMLElement;
  private messageList!: HTMLElement;
  private statusLine!: HTMLElement;
  private input!: HTMLTextAreaElement;
  private sendButton!: HTMLButtonElement;
  private humanButton!: HTMLButtonElement;
  private form!: HTMLFormElement;
  private ended!: HTMLElement;
  private ratingButtons: HTMLButtonElement[] = [];
  private ratingComment!: HTMLTextAreaElement;
  private ratingSubmit!: HTMLButtonElement;

  constructor(deps: ChatDeps) {
    this.deps = deps;
  }

  handleLoaderMessage(message: FromLoader): void {
    if (message.type === "init") {
      this.config = message.config;
      this.apiBase = message.apiBase.replace(/\/$/, "");
      this.build();
    } else {
      const grant = message.type === "session" ? message.session : null;
      if (message.type === "session-error") {
        this.setStatus("The chat is not available right now. Please try again later.", "error");
      }
      this.session = grant;
      const waiters = this.sessionWaiters;
      this.sessionWaiters = [];
      waiters.forEach((resolve) => resolve(grant));
    }
  }

  private build(): void {
    const config = this.config!;
    const root = this.deps.root;
    root.replaceChildren();
    root.style.setProperty("--servora-accent", config.theme_color);
    root.dataset.position = config.position === "BOTTOM_LEFT" ? "left" : "right";

    this.launcher = el("button", { type: "button", class: "launcher", "aria-label": `Open chat: ${config.title}`, "aria-expanded": "false" });
    this.launcher.append(icon("chat", 26));
    this.launcher.addEventListener("click", () => void this.openPanel());

    this.panel = el("section", { class: "panel", role: "dialog", "aria-label": config.title, hidden: "" });
    const header = el("header", { class: "header" });
    const avatar = el("span", { class: "avatar", "aria-hidden": "true" }, config.title.trim().slice(0, 1).toUpperCase());
    const titles = el("div", { class: "titles" });
    titles.append(el("h1", {}, config.title), el("p", { class: "subtitle" }, "AI assistant, with our team on hand"));
    header.append(avatar, titles);
    const close = el("button", { type: "button", class: "close", "aria-label": "Close chat" });
    close.append(icon("close", 18));
    close.addEventListener("click", () => this.closePanel());
    header.append(close);

    // The live region scrolls and can take keyboard focus, so the history can be read without a mouse.
    // The messages inside it stay a real list.
    this.log = el("div", { class: "log", role: "log", "aria-live": "polite", "aria-label": "Conversation", tabindex: "0" });
    this.messageList = el("ol", { class: "messages" });
    this.log.append(this.messageList);
    this.statusLine = el("p", { class: "status", role: "status" });

    const form = el("form", { class: "composer" });
    this.form = form;
    const label = el("label", { for: "servora-input", class: "visually-hidden" }, "Your message");
    this.input = el("textarea", { id: "servora-input", rows: "2", maxlength: String(MAX_LENGTH), placeholder: "Type your question…" });
    this.input.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        void this.send();
      }
    });
    this.sendButton = el("button", { type: "submit", class: "send", "aria-label": "Send" });
    this.sendButton.append(icon("send", 18));
    form.append(label, this.input, this.sendButton);
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      void this.send();
    });

    this.humanButton = el("button", { type: "button", class: "human" });
    this.humanButton.append(icon("person", 15), document.createTextNode("Talk to a person"));
    this.humanButton.addEventListener("click", () => void this.requestHuman());

    this.ended = this.buildEnded();
    this.panel.append(header, this.log, this.statusLine, form, this.humanButton, this.ended);
    if (config.show_branding) {
      this.panel.append(el("p", { class: "branding" }, "Powered by Servora"));
    }
    this.panel.addEventListener("keydown", (event) => {
      if (event.key === "Escape") {
        this.closePanel();
      }
    });

    root.append(this.launcher, this.panel);
    this.render();
  }

  private buildEnded(): HTMLElement {
    const section = el("div", { class: "ended", hidden: "" });
    section.append(el("p", { class: "ended-title" }, "How did we do?"));
    const stars = el("div", { class: "stars", role: "group", "aria-label": "Rate this conversation" });
    for (let value = 1; value <= 5; value++) {
      const star = el("button", { type: "button", class: "star", "aria-label": `${value} out of 5`, "aria-pressed": "false" }, "★");
      star.addEventListener("click", () => {
        this.rating = value;
        this.render();
      });
      this.ratingButtons.push(star);
      stars.append(star);
    }
    const label = el("label", { for: "servora-feedback", class: "visually-hidden" }, "Comment (optional)");
    this.ratingComment = el("textarea", { id: "servora-feedback", rows: "2", maxlength: "1000", placeholder: "Anything to add? (optional)" });
    this.ratingSubmit = el("button", { type: "button", class: "send" }, "Send rating");
    this.ratingSubmit.addEventListener("click", () => void this.sendFeedback());
    const restart = el("button", { type: "button", class: "human" }, "Start a new chat");
    restart.addEventListener("click", () => void this.startNewChat());
    section.append(stars, label, this.ratingComment, this.ratingSubmit, restart);
    return section;
  }

  async sendFeedback(): Promise<void> {
    if (this.rating < 1) {
      this.setStatus("Choose a rating from 1 to 5 stars.", "error");
      return;
    }
    try {
      const response = await this.call("/conversation/feedback", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ rating: this.rating, comment: this.ratingComment.value.trim() || null }),
      });
      if (response.ok || response.status === 409) {
        this.feedbackSubmitted = true;
        this.setStatus("Thank you for your feedback.", "info");
      } else {
        this.setStatus(await this.errorText(response), "error");
      }
    } catch {
      this.setStatus("You appear to be offline. Check your connection.", "error");
    }
  }

  async startNewChat(): Promise<void> {
    this.status = null;
    this.rating = 0;
    this.feedbackSubmitted = false;
    this.conversationStatus = "OPEN";
    this.session = null;
    this.messages = [];
    this.controlMode = "AI_ACTIVE";
    this.render();
    if (await this.requestSession(true)) {
      await this.loadHistory();
    }
    this.input.focus();
  }

  /** While a person handles the chat, replies arrive by checking the history every few seconds. */
  private schedulePoll(): void {
    const waitingForTeam = this.open && this.controlMode !== "AI_ACTIVE" && this.conversationStatus === "OPEN";
    if (!waitingForTeam) {
      if (this.pollTimer !== null) {
        clearTimeout(this.pollTimer);
        this.pollTimer = null;
      }
      return;
    }
    if (this.pollTimer === null) {
      this.pollTimer = setTimeout(() => {
        this.pollTimer = null;
        void this.loadHistory().finally(() => this.schedulePoll());
      }, this.deps.pollMs ?? 5000);
    }
  }

  async openPanel(): Promise<void> {
    this.open = true;
    this.deps.postToParent({ source: "servora-widget", type: "open" });
    this.render();
    this.input.focus();
    if (!this.session) {
      const session = await this.requestSession(false);
      if (session) {
        await this.loadHistory();
      }
    }
  }

  closePanel(): void {
    this.open = false;
    this.deps.postToParent({ source: "servora-widget", type: "close" });
    this.render();
    this.launcher.focus();
  }

  private requestSession(renew: boolean): Promise<SessionGrant | null> {
    return new Promise((resolve) => {
      this.sessionWaiters.push(resolve);
      this.deps.postToParent({ source: "servora-widget", type: "session-request", renew });
    });
  }

  private async call(path: string, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${this.session?.token ?? ""}`);
    return this.deps.fetch(`${this.apiBase}/api/v1/public${path}`, { ...init, headers, credentials: "omit" });
  }

  private async loadHistory(): Promise<void> {
    try {
      const response = await this.call("/conversation");
      if (response.status === 401) {
        await this.renewSession();
        return;
      }
      if (response.ok) {
        this.applyConversation((await response.json()) as ConversationView);
      }
    } catch {
      this.setStatus("You appear to be offline. Check your connection.", "error");
    }
  }

  private applyConversation(view: ConversationView): void {
    this.messages = view.messages;
    this.controlMode = view.control_mode;
    this.conversationStatus = view.status;
    this.feedbackSubmitted = view.feedback_submitted === true;
    this.render();
  }

  private async renewSession(): Promise<boolean> {
    this.session = null;
    this.messages = [];
    this.controlMode = "AI_ACTIVE";
    const session = await this.requestSession(true);
    if (session) {
      this.setStatus("Your previous chat session ended, so a new chat has started.", "info");
    }
    return session !== null;
  }

  async send(): Promise<void> {
    const text = this.input.value.trim();
    if (!text || this.sending) {
      return;
    }
    this.sending = true;
    this.status = null;
    this.render();
    try {
      if (!this.session && !(await this.requestSession(false))) {
        return;
      }
      const key = this.deps.newKey();
      let response = await this.postMessage(text, key);
      if (response.status === 401 && (await this.renewSession())) {
        response = await this.postMessage(text, key);
      }
      if (!response.ok) {
        this.setStatus(await this.errorText(response), "error");
        if (response.status === 409) {
          await this.loadHistory();
        }
        return;
      }
      this.input.value = "";
      await readEvents(response, (event) => this.onEvent(event.event, event.data));
    } catch {
      this.setStatus("The connection was interrupted. Your message may have been sent; reopen the chat to check.", "error");
    } finally {
      this.sending = false;
      this.typing = false;
      this.render();
    }
  }

  private postMessage(text: string, key: string): Promise<Response> {
    return this.call("/conversation/messages", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": key, Accept: "text/event-stream" },
      body: JSON.stringify({ text }),
    });
  }

  private onEvent(name: string, data: string): void {
    const payload = data ? (JSON.parse(data) as unknown) : {};
    switch (name) {
      case "customer_message":
      case "assistant_message":
      case "system_message":
        this.typing = false;
        this.upsert(payload as ChatMessage);
        break;
      case "assistant_typing":
        this.typing = true;
        break;
      case "notice": {
        const code = (payload as { code?: string }).code ?? "";
        if (code === "HUMAN_PENDING" || code === "SUPPRESSED" || code === "AUTOMATION_OFF") {
          this.controlMode = "HUMAN_PENDING";
        }
        this.status = { text: NOTICES[code] ?? "", tone: "info" };
        break;
      }
      case "error":
        this.status = { text: "Sorry, something went wrong. Please try again.", tone: "error" };
        break;
      default:
        break;
    }
    this.render();
  }

  private upsert(message: ChatMessage): void {
    const index = this.messages.findIndex((m) => m.id === message.id);
    if (index === -1) {
      this.messages = [...this.messages, message];
    } else {
      this.messages = this.messages.map((m, i) => (i === index ? message : m));
    }
  }

  async requestHuman(): Promise<void> {
    if (!this.session && !(await this.requestSession(false))) {
      return;
    }
    try {
      const response = await this.call("/conversation/handoff", { method: "POST" });
      if (response.ok) {
        // The conversation now ends with the server's own notice, so no status line is added.
        this.status = null;
        this.applyConversation((await response.json()) as ConversationView);
      } else {
        this.setStatus(await this.errorText(response), "error");
      }
    } catch {
      this.setStatus("You appear to be offline. Check your connection.", "error");
    }
  }

  private async errorText(response: Response): Promise<string> {
    if (response.status === 429) {
      return "You are sending messages very quickly. Please wait a moment.";
    }
    try {
      const body = (await response.json()) as { error?: { message?: string } };
      if (body.error?.message) {
        return body.error.message;
      }
    } catch {
      // Not JSON.
    }
    return "Sorry, something went wrong. Please try again.";
  }

  private setStatus(text: string, tone: "info" | "error"): void {
    this.status = { text, tone };
    this.render();
  }

  private render(): void {
    if (!this.config) {
      return;
    }
    const ended = this.conversationStatus === "CLOSED";
    this.launcher.hidden = this.open;
    this.launcher.setAttribute("aria-expanded", String(this.open));
    this.panel.hidden = !this.open;
    this.sendButton.disabled = this.sending;
    this.humanButton.hidden = this.controlMode !== "AI_ACTIVE" || ended;
    this.form.hidden = ended;
    this.ended.hidden = !ended;
    this.ratingButtons.forEach((star, index) => {
      star.setAttribute("aria-pressed", String(index < this.rating));
      star.disabled = this.feedbackSubmitted;
    });
    this.ratingComment.hidden = this.feedbackSubmitted;
    this.ratingSubmit.hidden = this.feedbackSubmitted;

    const items: HTMLElement[] = [];
    if (this.config.welcome_message) {
      items.push(this.bubble("ASSISTANT", this.config.welcome_message, []));
    }
    for (const message of this.messages) {
      items.push(this.bubble(message.sender_type, message.body, message.citations));
    }
    if (this.typing) {
      items.push(el("li", { class: "message typing", "aria-label": "The assistant is typing" }, "…"));
    }
    this.messageList.replaceChildren(...items);
    this.log.scrollTop = this.log.scrollHeight;

    this.statusLine.textContent = this.status?.text ?? "";
    this.statusLine.dataset.tone = this.status?.tone ?? "info";
    this.schedulePoll();
  }

  private bubble(sender: string, body: string, citations: Citation[]): HTMLElement {
    const item = el("li", { class: `message ${sender.toLowerCase()}` });
    const who = sender === "CUSTOMER" ? "You" : sender === "SYSTEM" ? "Notice" : sender === "AGENT" ? "Team" : "Assistant";
    item.append(el("span", { class: "visually-hidden" }, `${who}: `), el("p", { class: "body" }, body));
    if (citations.length > 0) {
      const sources = el("div", { class: "sources" });
      sources.append(el("p", { class: "sources-title" }, "Sources"));
      const list = el("ul");
      for (const citation of citations) {
        const entry = el("li", { title: citation.snippet });
        entry.append(icon("document", 12), document.createTextNode(describeCitation(citation)));
        list.append(entry);
      }
      sources.append(list);
      item.append(sources);
    }
    return item;
  }

  /** For tests. */
  get state() {
    return {
      open: this.open,
      messages: this.messages,
      controlMode: this.controlMode,
      conversationStatus: this.conversationStatus,
      status: this.status,
    };
  }
}
