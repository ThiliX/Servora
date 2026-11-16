import { describe, expect, it, vi } from "vitest";
import { errorResponse, headerOf, jsonResponse, mockFetch, SESSION } from "../test/helpers";
import { api, ApiError, login, refreshSession, setSessionLostHandler } from "./api";

describe("api client", () => {
  it("sends the access token as a bearer header after login", async () => {
    const { calls } = mockFetch((url) =>
      url.endsWith("/auth/login") ? jsonResponse(200, SESSION) : jsonResponse(200, []),
    );

    await login("owner@example.test", "password-123");
    await api("/workspaces");

    expect(headerOf(calls[0]!.init, "Authorization")).toBeUndefined();
    expect(headerOf(calls[1]!.init, "Authorization")).toBe("Bearer access-1");
    expect(calls[1]!.url).toBe("/api/v1/workspaces");
  });

  it("never stores the access token in web storage", async () => {
    mockFetch(() => jsonResponse(200, SESSION));

    await login("owner@example.test", "password-123");

    expect(JSON.stringify({ ...localStorage })).not.toContain("access-1");
    expect(JSON.stringify({ ...sessionStorage })).not.toContain("access-1");
  });

  it("refreshes once and retries when the access token has expired", async () => {
    let workspaceCalls = 0;
    const { calls } = mockFetch((url) => {
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse(200, { ...SESSION, access_token: "access-2" });
      }
      workspaceCalls += 1;
      return workspaceCalls === 1 ? errorResponse(401, "UNAUTHENTICATED") : jsonResponse(200, [{ id: "w1" }]);
    });

    const result = await api<{ id: string }[]>("/workspaces");

    expect(result).toEqual([{ id: "w1" }]);
    expect(calls.map((call) => call.url)).toEqual([
      "/api/v1/workspaces",
      "/api/v1/auth/refresh",
      "/api/v1/workspaces",
    ]);
    expect(headerOf(calls[1]!.init, "X-Servora-Client")).toBe("web");
    expect(headerOf(calls[2]!.init, "Authorization")).toBe("Bearer access-2");
  });

  it("shares a single refresh between concurrent requests", async () => {
    let refreshed = false;
    const { calls } = mockFetch(async (url) => {
      if (url.endsWith("/auth/refresh")) {
        await new Promise((resolve) => setTimeout(resolve, 5));
        refreshed = true;
        return jsonResponse(200, SESSION);
      }
      return refreshed ? jsonResponse(200, { ok: true }) : errorResponse(401, "UNAUTHENTICATED");
    });

    await Promise.all([api("/workspaces"), api("/workspaces/w1"), api("/workspaces/w1/members")]);

    // The server rotates the refresh cookie on every use, so a second refresh would be a replay.
    expect(calls.filter((call) => call.url.endsWith("/auth/refresh"))).toHaveLength(1);
  });

  it("reports a lost session when the refresh is rejected", async () => {
    const sessionLost = vi.fn();
    setSessionLostHandler(sessionLost);
    mockFetch((url) =>
      url.endsWith("/auth/refresh") ? errorResponse(401, "INVALID_SESSION") : errorResponse(401, "UNAUTHENTICATED"),
    );

    await expect(api("/workspaces")).rejects.toMatchObject({ status: 401, code: "UNAUTHENTICATED" });
    expect(sessionLost).toHaveBeenCalledOnce();
  });

  it("does not attempt a refresh for anonymous endpoints", async () => {
    const { calls } = mockFetch(() => errorResponse(401, "INVALID_CREDENTIALS", "The email or password is incorrect."));

    await expect(login("owner@example.test", "wrong")).rejects.toMatchObject({ code: "INVALID_CREDENTIALS" });
    expect(calls).toHaveLength(1);
  });

  it("parses the error envelope including field details and trace ID", async () => {
    mockFetch(() =>
      jsonResponse(400, {
        error: {
          code: "VALIDATION_FAILED",
          message: "The request is invalid.",
          trace_id: "trace-9",
          details: [{ field: "password", message: "size must be between 10 and 128" }],
        },
      }),
    );

    const error = await api("/auth/register", { method: "POST", body: {}, anonymous: true }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 400, code: "VALIDATION_FAILED", traceId: "trace-9" });
    expect((error as ApiError).details).toEqual([{ field: "password", message: "size must be between 10 and 128" }]);
  });

  it("turns a network failure into an offline error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    const error = await api("/workspaces").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).isOffline).toBe(true);
  });

  it("returns null from refresh when there is no session", async () => {
    mockFetch(() => errorResponse(401, "INVALID_SESSION"));

    await expect(refreshSession()).resolves.toBeNull();
  });

  it("returns undefined for 204 responses", async () => {
    mockFetch(() => new Response(null, { status: 204 }));

    await expect(api("/auth/verify-email", { method: "POST", body: { token: "t" }, anonymous: true })).resolves.toBeUndefined();
  });
});
