/** Messages between the loader (on the customer's website) and the widget iframe. */

export interface WidgetConfig {
  title: string;
  theme_color: string;
  position: "BOTTOM_RIGHT" | "BOTTOM_LEFT";
  show_branding: boolean;
  welcome_message: string;
}

export interface SessionGrant {
  token: string;
  conversation_id: string;
  expires_at: string;
}

export type FromWidget =
  | { source: "servora-widget"; type: "ready" }
  | { source: "servora-widget"; type: "open" }
  | { source: "servora-widget"; type: "close" }
  | { source: "servora-widget"; type: "session-request"; renew: boolean };

export type FromLoader =
  | { source: "servora-loader"; type: "init"; config: WidgetConfig; apiBase: string }
  | { source: "servora-loader"; type: "session"; session: SessionGrant }
  | { source: "servora-loader"; type: "session-error"; code: string };

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

/** Narrows an untrusted postMessage payload to a widget message, or null. */
export function asWidgetMessage(data: unknown): FromWidget | null {
  if (!isObject(data) || data.source !== "servora-widget") {
    return null;
  }
  switch (data.type) {
    case "ready":
    case "open":
    case "close":
      return { source: "servora-widget", type: data.type };
    case "session-request":
      return { source: "servora-widget", type: "session-request", renew: data.renew === true };
    default:
      return null;
  }
}

/** Narrows an untrusted postMessage payload to a loader message, or null. */
export function asLoaderMessage(data: unknown): FromLoader | null {
  if (!isObject(data) || data.source !== "servora-loader") {
    return null;
  }
  if (data.type === "init" && isObject(data.config) && typeof data.apiBase === "string") {
    const c = data.config;
    if (typeof c.title !== "string" || typeof c.theme_color !== "string" || typeof c.welcome_message !== "string") {
      return null;
    }
    return {
      source: "servora-loader",
      type: "init",
      apiBase: data.apiBase,
      config: {
        title: c.title,
        theme_color: /^#[0-9a-f]{6}$/i.test(c.theme_color) ? c.theme_color : "#4f46e5",
        position: c.position === "BOTTOM_LEFT" ? "BOTTOM_LEFT" : "BOTTOM_RIGHT",
        show_branding: c.show_branding !== false,
        welcome_message: c.welcome_message,
      },
    };
  }
  if (data.type === "session" && isObject(data.session)) {
    const s = data.session;
    if (typeof s.token === "string" && typeof s.conversation_id === "string" && typeof s.expires_at === "string") {
      return {
        source: "servora-loader",
        type: "session",
        session: { token: s.token, conversation_id: s.conversation_id, expires_at: s.expires_at },
      };
    }
    return null;
  }
  if (data.type === "session-error" && typeof data.code === "string") {
    return { source: "servora-loader", type: "session-error", code: data.code };
  }
  return null;
}
