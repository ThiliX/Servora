import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Outlet, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import { App } from "../App";
import { AuthProvider } from "../lib/auth";
import type { Member, Workspace, WorkspaceRole } from "../lib/types";
import { errorResponse, jsonResponse, mockFetch, SESSION } from "../test/helpers";
import { tokenFromHash } from "./AuthShell";
import { TeamPage } from "./TeamPage";
import type { WorkspaceOutletContext } from "./WorkspaceLayout";
import { suggestSlug } from "./WorkspacesPage";

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

const WORKSPACE: Workspace = {
  id: "w1",
  name: "TechStore",
  slug: "techstore",
  role: "OWNER",
  status: "ACTIVE",
  timezone: "UTC",
  default_language: "en",
  industry: null,
  logo_url: null,
  created_at: "2026-11-16T10:00:00Z",
};

const MEMBERS: Member[] = [
  { id: "m1", user_id: "user-1", email: "owner@example.test", display_name: "Owner", role: "OWNER", status: "ACTIVE", joined_at: "2026-11-16T10:00:00Z" },
  { id: "m2", user_id: "user-2", email: "agent@example.test", display_name: "Agent", role: "AGENT", status: "ACTIVE", joined_at: "2026-11-16T10:00:00Z" },
];

function renderTeam(role: WorkspaceRole, userId: string) {
  const context: WorkspaceOutletContext = {
    workspace: { ...WORKSPACE, role },
    user: { id: userId, email: "me@example.test", display_name: "Me" },
    reloadWorkspace: () => {},
  };
  return render(
    <MemoryRouter initialEntries={["/team"]}>
      <Routes>
        <Route element={<Outlet context={context} />}>
          <Route path="/team" element={<TeamPage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

function teamApi(url: string): Response {
  if (url.includes("/members")) {
    return jsonResponse(200, { items: MEMBERS, page: 0, size: 100, total: 2 });
  }
  return jsonResponse(200, { items: [], page: 0, size: 100, total: 0 });
}

describe("sign in", () => {
  it("redirects a signed-out visitor to the login page", async () => {
    mockFetch(() => errorResponse(401, "INVALID_SESSION"));

    renderApp("/");

    expect(await screen.findByRole("heading", { name: "Sign in" })).toBeInTheDocument();
  });

  it("shows the server's message when credentials are rejected", async () => {
    mockFetch((url) =>
      url.endsWith("/auth/login")
        ? errorResponse(401, "INVALID_CREDENTIALS", "The email or password is incorrect.")
        : errorResponse(401, "INVALID_SESSION"),
    );
    const user = userEvent.setup();
    renderApp("/login");

    await user.type(await screen.findByLabelText("Email"), "owner@example.test");
    await user.type(screen.getByLabelText("Password"), "wrong-password");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The email or password is incorrect.");
  });

  it("signs in and shows the user's workspaces", async () => {
    mockFetch((url) => {
      if (url.endsWith("/auth/refresh")) {
        return errorResponse(401, "INVALID_SESSION");
      }
      if (url.endsWith("/auth/login")) {
        return jsonResponse(200, SESSION);
      }
      return jsonResponse(200, [{ id: "w1", name: "TechStore", slug: "techstore", role: "OWNER" }]);
    });
    const user = userEvent.setup();
    renderApp("/login");

    await user.type(await screen.findByLabelText("Email"), "owner@example.test");
    await user.type(screen.getByLabelText("Password"), "correct-password");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("link", { name: /TechStore/ })).toBeInTheDocument();
  });

  it("explains when the server cannot be reached", async () => {
    let online = true;
    mockFetch((url) => {
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse(200, SESSION);
      }
      if (!online) {
        throw new TypeError("Failed to fetch");
      }
      return jsonResponse(200, []);
    });
    online = false;

    renderApp("/");

    expect(await screen.findByRole("alert")).toHaveTextContent(/offline/i);
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("shows an empty state when the user has no workspaces", async () => {
    mockFetch((url) => (url.endsWith("/auth/refresh") ? jsonResponse(200, SESSION) : jsonResponse(200, [])));

    renderApp("/");

    expect(await screen.findByText(/not a member of any workspace yet/i)).toBeInTheDocument();
  });

  it("tells a non-member the workspace is unavailable", async () => {
    mockFetch((url) => {
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse(200, SESSION);
      }
      if (url.endsWith("/workspaces/other")) {
        return errorResponse(404, "WORKSPACE_NOT_FOUND", "The workspace was not found.");
      }
      return jsonResponse(200, []);
    });

    renderApp("/w/other/team");

    expect(await screen.findByRole("alert")).toHaveTextContent(/not a member of it/i);
  });
});

describe("team page", () => {
  it("lets an owner manage other members and invitations, but not themselves", async () => {
    mockFetch(teamApi);

    renderTeam("OWNER", "user-1");

    expect(await screen.findByLabelText("Role for Agent")).toBeInTheDocument();
    expect(screen.queryByLabelText("Role for Owner")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send invitation" })).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Disable" })).toHaveLength(1);
  });

  it("shows an agent the member list with no management controls", async () => {
    const { calls } = mockFetch(teamApi);

    renderTeam("AGENT", "user-2");

    expect(await screen.findByText("agent@example.test")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Disable" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Send invitation" })).not.toBeInTheDocument();
    // The invitations endpoint is not even requested for a role that cannot use it.
    await waitFor(() => expect(calls.some((call) => call.url.includes("/invitations"))).toBe(false));
  });

  it("does not let an admin edit an owner", async () => {
    mockFetch(teamApi);

    renderTeam("ADMIN", "user-9");

    expect(await screen.findByLabelText("Role for Agent")).toBeInTheDocument();
    expect(screen.queryByLabelText("Role for Owner")).not.toBeInTheDocument();
    const roleOptions = Array.from(screen.getByLabelText("Role for Agent").querySelectorAll("option")).map(
      (option) => option.value,
    );
    expect(roleOptions).not.toContain("OWNER");
  });

  it("shows a permission message when the server refuses", async () => {
    mockFetch(() => errorResponse(403, "FORBIDDEN", "You do not have permission to perform this action."));

    renderTeam("AGENT", "user-2");

    expect(await screen.findByRole("alert")).toHaveTextContent(/do not have permission/i);
  });
});

describe("helpers", () => {
  it("suggests a valid workspace address from a business name", () => {
    expect(suggestSlug("  Tech Store & Co.  ")).toBe("tech-store-co");
    expect(suggestSlug("Café Niño")).toBe("cafe-nino");
  });

  it("reads a token only from the URL fragment", () => {
    expect(tokenFromHash("#token=abc123")).toBe("abc123");
    expect(tokenFromHash("")).toBeNull();
    expect(tokenFromHash("#other=1")).toBeNull();
  });
});
