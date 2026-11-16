import { vi } from "vitest";

export function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

export function errorResponse(status: number, code: string, message = "Failed."): Response {
  return jsonResponse(status, { error: { code, message, trace_id: "trace-123" } });
}

export const SESSION = {
  access_token: "access-1",
  token_type: "Bearer",
  expires_in: 900,
  user: { id: "user-1", email: "owner@example.test", display_name: "Owner" },
};

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>;

/** Replaces global fetch with a handler and records every call. */
export function mockFetch(handler: Handler) {
  const calls: { url: string; init: RequestInit }[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input);
    calls.push({ url, init });
    return handler(url, init);
  });
  vi.stubGlobal("fetch", fetchMock);
  return { calls, fetchMock };
}

export function headerOf(init: RequestInit, name: string): string | undefined {
  return (init.headers as Record<string, string> | undefined)?.[name];
}
