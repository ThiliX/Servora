import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";
import { App } from "../App";
import { AuthProvider } from "../lib/auth";
import { LIVE_DEMO_CONFIG_URL, parseLiveDemoConfig } from "../lib/liveDemo";
import { errorResponse, jsonResponse, mockFetch, SESSION } from "../test/helpers";

const CONFIG = {
  reset: "every night",
  accounts: [
    { label: "TechStore owner", email: "owner@example.test", password: "shared-demo-password" },
    { label: "TechStore agent", email: "agent@example.test", password: "another-password" },
  ],
  sites: [{ label: "TechStore website", url: "https://demo.example.test:10000/techstore/?key=wk_1" }],
};

function renderLogin() {
  return render(
    <MemoryRouter initialEntries={["/login"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("live demo", () => {
  it("offers one-click sign-in to the shared accounts and hides registration", async () => {
    const { calls } = mockFetch((url) => {
      if (url === LIVE_DEMO_CONFIG_URL) return jsonResponse(200, CONFIG);
      if (url.endsWith("/auth/login")) return jsonResponse(200, SESSION);
      if (url.endsWith("/auth/refresh")) return errorResponse(401, "INVALID_SESSION");
      return jsonResponse(200, []);
    });
    const user = userEvent.setup();
    renderLogin();

    await user.click(await screen.findByRole("button", { name: "Sign in as TechStore owner" }));

    const login = calls.find((call) => call.url.endsWith("/auth/login"))!;
    expect(JSON.parse(login.init.body as string)).toEqual({ email: "owner@example.test", password: "shared-demo-password" });
    expect(screen.queryByRole("link", { name: "Create an account" })).not.toBeInTheDocument();
  });

  it("shows the normal sign-in page when there is no demo", async () => {
    mockFetch((url) => (url === LIVE_DEMO_CONFIG_URL ? new Response("<!doctype html>", { status: 200, headers: { "Content-Type": "text/html" } }) : errorResponse(401, "INVALID_SESSION")));
    renderLogin();

    expect(await screen.findByRole("link", { name: "Create an account" })).toBeInTheDocument();
    expect(screen.queryByText("Try the live demo")).not.toBeInTheDocument();
  });

  it("accepts only the expected shape", () => {
    expect(parseLiveDemoConfig(CONFIG)?.accounts).toHaveLength(2);
    expect(parseLiveDemoConfig([])).toBeNull();
    expect(parseLiveDemoConfig({ ...CONFIG, accounts: [] })).toBeNull();
    expect(parseLiveDemoConfig({ ...CONFIG, sites: [{ label: "Bad", url: "javascript:alert(1)" }] })?.sites).toEqual([]);
  });
});
