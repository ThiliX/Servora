import type { SessionResponse, User } from "./types";

const BASE = "/api/v1";
/** Required by the cookie-authenticated endpoints; a cross-site page cannot set it without CORS. */
const CLIENT_HEADER = { "X-Servora-Client": "web" };

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId: string | undefined;
  readonly details: { field: string; message: string }[];

  constructor(
    status: number,
    code: string,
    message: string,
    traceId?: string,
    details: { field: string; message: string }[] = [],
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.traceId = traceId;
    this.details = details;
  }

  get isOffline(): boolean {
    return this.code === "NETWORK_ERROR";
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }
}

// The access token lives only in this module's memory. It is never written to
// localStorage or sessionStorage, so it does not survive a reload; the HttpOnly
// refresh cookie restores the session instead.
let accessToken: string | null = null;
let refreshInFlight: Promise<User | null> | null = null;
let onSessionLost: (() => void) | null = null;

export function setSessionLostHandler(handler: (() => void) | null): void {
  onSessionLost = handler;
}

async function send(path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(BASE + path, { credentials: "same-origin", ...init });
  } catch {
    throw new ApiError(0, "NETWORK_ERROR", "Cannot reach the server. Check your connection and try again.");
  }
}

async function toError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as {
      error?: { code?: string; message?: string; trace_id?: string; details?: { field: string; message: string }[] };
    };
    if (body.error?.code) {
      return new ApiError(
        response.status,
        body.error.code,
        body.error.message ?? "The request failed.",
        body.error.trace_id,
        body.error.details ?? [],
      );
    }
  } catch {
    // Not a JSON error envelope; fall through.
  }
  return new ApiError(response.status, "UNEXPECTED_RESPONSE", "The request failed.");
}

async function parse<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

function acceptSession(session: SessionResponse): User {
  accessToken = session.access_token;
  return session.user;
}

/**
 * Exchanges the refresh cookie for a new access token. Concurrent callers share one request,
 * because the server rotates the cookie on every use and treats a replay as theft.
 */
export function refreshSession(): Promise<User | null> {
  refreshInFlight ??= (async () => {
    try {
      const response = await send("/auth/refresh", { method: "POST", headers: CLIENT_HEADER });
      if (!response.ok) {
        accessToken = null;
        return null;
      }
      return acceptSession(await parse<SessionResponse>(response));
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

interface RequestOptions {
  method?: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
  /** Public endpoints skip the Authorization header and the refresh-and-retry step. */
  anonymous?: boolean;
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, anonymous = false } = options;
  const request = (): Promise<Response> => {
    const headers: Record<string, string> = {};
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    if (!anonymous && accessToken) {
      headers["Authorization"] = `Bearer ${accessToken}`;
    }
    return send(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  };

  let response = await request();
  if (response.status === 401 && !anonymous) {
    // The access token expired: refresh once and retry.
    const user = await refreshSession();
    if (!user) {
      onSessionLost?.();
      throw await toError(response);
    }
    response = await request();
  }
  if (!response.ok) {
    throw await toError(response);
  }
  return parse<T>(response);
}

export async function login(email: string, password: string): Promise<User> {
  const session = await api<SessionResponse>("/auth/login", {
    method: "POST",
    body: { email, password },
    anonymous: true,
  });
  return acceptSession(session);
}

export async function logout(): Promise<void> {
  accessToken = null;
  await send("/auth/logout", { method: "POST", headers: CLIENT_HEADER });
}

/** Test hook: clears module state between tests. */
export function resetApiStateForTests(): void {
  accessToken = null;
  refreshInFlight = null;
  onSessionLost = null;
}
