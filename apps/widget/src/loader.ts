import { asWidgetMessage, type FromLoader, type SessionGrant, type WidgetConfig } from "./protocol";

/**
 * The script a website embeds:
 *
 *   <script type="module" src="https://WIDGET-HOST/servora-widget.js" data-widget-key="wk_..." async></script>
 *
 * It runs on the customer's website, so its requests carry that website's Origin, which the API
 * checks against the widget's allowed origins. It mounts the chat iframe, starts sessions on the
 * iframe's request, and hands the session token to the iframe and to nobody else.
 */

const LAUNCHER = { width: "80px", height: "80px" };
const PANEL = { width: "400px", height: "640px" };
const RENEW_BEFORE_MS = 60_000;

export interface LoaderOptions {
  publicKey: string;
  widgetOrigin: string;
  apiBase: string;
  fetch?: typeof fetch;
  storage?: Storage | null;
}

export function storageKey(publicKey: string): string {
  return `servora:session:${publicKey}`;
}

/** A stored session that is still usable, or null. */
export function reusableSession(stored: string | null, now: number): SessionGrant | null {
  if (!stored) {
    return null;
  }
  try {
    const session = JSON.parse(stored) as SessionGrant;
    if (typeof session.token !== "string" || Date.parse(session.expires_at) - now < RENEW_BEFORE_MS) {
      return null;
    }
    return session;
  } catch {
    return null;
  }
}

function safeStorage(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

export async function mount(options: LoaderOptions): Promise<HTMLIFrameElement | null> {
  const fetcher = options.fetch ?? window.fetch.bind(window);
  const storage = options.storage === undefined ? safeStorage() : options.storage;
  const base = `${options.apiBase.replace(/\/$/, "")}/api/v1/public/widgets/${encodeURIComponent(options.publicKey)}`;

  let config: WidgetConfig;
  try {
    const response = await fetcher(`${base}/config`, { credentials: "omit" });
    if (!response.ok) {
      console.warn(`Servora widget not shown (status ${response.status}). Check the widget key and allowed websites.`);
      return null;
    }
    config = (await response.json()) as WidgetConfig;
  } catch {
    // A browser hides a refused cross-origin response, so a website missing from the allowed list
    // looks the same as an unreachable service.
    console.warn("Servora widget not shown: this website is not on the widget's allowed list, or the service could not be reached.");
    return null;
  }

  const iframe = document.createElement("iframe");
  iframe.src = `${options.widgetOrigin}/widget.html`;
  iframe.title = config.title || "Chat";
  iframe.setAttribute("allow", "");
  iframe.setAttribute("referrerpolicy", "no-referrer");
  Object.assign(iframe.style, {
    position: "fixed",
    bottom: "0",
    [config.position === "BOTTOM_LEFT" ? "left" : "right"]: "0",
    width: LAUNCHER.width,
    height: LAUNCHER.height,
    border: "0",
    background: "transparent",
    colorScheme: "normal",
    zIndex: "2147483000",
    maxWidth: "100vw",
    maxHeight: "100vh",
  });

  function post(message: FromLoader): void {
    iframe.contentWindow?.postMessage(message, options.widgetOrigin);
  }

  function resize(open: boolean): void {
    const narrow = window.innerWidth < 480;
    iframe.style.width = open ? (narrow ? "100vw" : PANEL.width) : LAUNCHER.width;
    iframe.style.height = open ? (narrow ? "100vh" : PANEL.height) : LAUNCHER.height;
  }

  async function session(renew: boolean): Promise<void> {
    const key = storageKey(options.publicKey);
    const stored = renew ? null : reusableSession(storage?.getItem(key) ?? null, Date.now());
    if (stored) {
      post({ source: "servora-loader", type: "session", session: stored });
      return;
    }
    try {
      const response = await fetcher(`${base}/sessions`, { method: "POST", credentials: "omit" });
      if (!response.ok) {
        post({ source: "servora-loader", type: "session-error", code: String(response.status) });
        return;
      }
      const grant = (await response.json()) as SessionGrant;
      const value: SessionGrant = { token: grant.token, conversation_id: grant.conversation_id, expires_at: grant.expires_at };
      try {
        storage?.setItem(key, JSON.stringify(value));
      } catch {
        // Storage full or blocked: the session simply lasts for this page view.
      }
      post({ source: "servora-loader", type: "session", session: value });
    } catch {
      post({ source: "servora-loader", type: "session-error", code: "OFFLINE" });
    }
  }

  window.addEventListener("message", (event) => {
    if (event.origin !== options.widgetOrigin || event.source !== iframe.contentWindow) {
      return;
    }
    const message = asWidgetMessage(event.data);
    if (!message) {
      return;
    }
    switch (message.type) {
      case "ready":
        post({ source: "servora-loader", type: "init", config, apiBase: options.apiBase });
        break;
      case "open":
        resize(true);
        break;
      case "close":
        resize(false);
        break;
      case "session-request":
        void session(message.renew);
        break;
    }
  });

  document.body.append(iframe);
  return iframe;
}

function start(): void {
  const script = document.querySelector<HTMLScriptElement>("script[data-widget-key]");
  const publicKey = script?.dataset.widgetKey;
  if (!publicKey) {
    console.warn("Servora widget: add data-widget-key to the script tag.");
    return;
  }
  const widgetOrigin = new URL(import.meta.url).origin;
  void mount({ publicKey, widgetOrigin, apiBase: script.dataset.apiBase ?? widgetOrigin });
}

if (typeof document !== "undefined" && !import.meta.env?.VITEST) {
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start, { once: true });
  } else {
    start();
  }
}
