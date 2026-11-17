import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Outlet, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import { App } from "../App";
import { formatDuration } from "../components/metrics";
import { AuthProvider } from "../lib/auth";
import type { Analytics, UsageReport, Workspace, WorkspaceRole } from "../lib/types";
import { errorResponse, jsonResponse, mockFetch, SESSION } from "../test/helpers";
import { AuditLogPage } from "./AuditLogPage";
import { OverviewPage } from "./OverviewPage";
import { SettingsPage } from "./SettingsPage";
import { UsagePage } from "./UsagePage";
import type { WorkspaceOutletContext } from "./WorkspaceLayout";

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
  conversation_retention_days: null,
  created_at: "2026-11-01T10:00:00Z",
};

const ANALYTICS: Analytics = {
  range: { from: "2026-11-11", to: "2026-11-17", days: 7 },
  conversations: { total: 10, resolved_by_ai: 6, escalated: 3, unanswered: 1 },
  daily: [
    { date: "2026-11-16", conversations: 4, escalated: 1 },
    { date: "2026-11-17", conversations: 6, escalated: 2 },
  ],
  queue: { open: 2, in_progress: 1, waiting_for_customer: 0, unassigned: 2, by_priority: { HIGH: 1 } },
  responses: {
    tickets_created: 3,
    tickets_resolved: 1,
    median_first_response_seconds: 90,
    median_resolution_seconds: null,
    median_ai_latency_ms: 1500,
  },
  intents: [{ key: "refund_request", count: 4 }],
  ai_outcomes: [{ key: "ANSWERED", count: 6 }],
  escalation_reasons: [{ key: "INSUFFICIENT_EVIDENCE", count: 2 }],
  resolution_outcomes: [],
  satisfaction: { average: 4.5, count: 2, distribution: { "5": 1, "4": 1 } },
};

const USAGE: UsageReport = {
  plan: { code: "FREE", name: "Free", monthly_ai_requests: 300, monthly_tokens: 500000, monthly_conversations: 200, max_documents: 20 },
  period_start: "2026-11-01T00:00:00Z",
  period_end: "2026-12-01T00:00:00Z",
  used: {
    ai_requests: 300,
    input_tokens: 1000,
    output_tokens: 200,
    tokens: 1200,
    estimated_cost_usd: null,
    conversations: 170,
    documents: 3,
  },
  cost_configured: false,
  by_source: [{ source: "WIDGET", requests: 300, tokens: 1200, estimated_cost_usd: null }],
  daily: [{ date: "2026-11-17", ai_requests: 300, tokens: 1200, conversations: 170 }],
  plans: [
    { code: "FREE", name: "Free", monthly_ai_requests: 300, monthly_tokens: 500000, monthly_conversations: 200, max_documents: 20 },
    { code: "STARTER", name: "Starter", monthly_ai_requests: 3000, monthly_tokens: 5000000, monthly_conversations: 2000, max_documents: 100 },
  ],
};

function renderPage(path: string, element: React.ReactNode, role: WorkspaceRole = "OWNER") {
  const context: WorkspaceOutletContext = {
    workspace: { ...WORKSPACE, role },
    user: { id: "u1", email: "owner@example.test", display_name: "Owner" },
    reloadWorkspace: () => {},
  };
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Outlet context={context} />}>
          <Route path={path} element={element} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

function renderApp(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("overview", () => {
  it("shows conversation, queue and response metrics, and reloads for another period", async () => {
    const { calls } = mockFetch(() => jsonResponse(200, ANALYTICS));
    const user = userEvent.setup();
    renderPage("/overview", <OverviewPage />);

    expect(await screen.findByText("Resolved by AI")).toBeInTheDocument();
    expect(screen.getByText("60% of conversations")).toBeInTheDocument();
    expect(screen.getByText("2 min")).toBeInTheDocument();
    expect(screen.getByText("4.5 / 5")).toBeInTheDocument();
    expect(screen.getByText("Refund request")).toBeInTheDocument();
    expect(screen.getByText("No tickets resolved in this period.")).toBeInTheDocument();
    // The daily chart has a table view with the same numbers.
    const table = screen.getAllByRole("table")[0]!;
    expect(within(table).getByText("2026-11-17")).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("Period"), "7");
    await waitFor(() => expect(calls.some((c) => c.url.endsWith("/analytics?days=7"))).toBe(true));
  });

  it("tells an agent they cannot see metrics without asking the server", async () => {
    const { calls } = mockFetch(() => jsonResponse(200, ANALYTICS));
    renderPage("/overview", <OverviewPage />, "AGENT");
    expect(await screen.findByText(/do not have permission to view workspace metrics/)).toBeInTheDocument();
    expect(calls.length).toBeLessThanOrEqual(1);
  });

  it("formats durations for people", () => {
    expect(formatDuration(null)).toBe("–");
    expect(formatDuration(42)).toBe("42 s");
    expect(formatDuration(5400)).toBe("1.5 h");
  });
});

describe("usage and plan", () => {
  it("shows usage against limits, flags a used-up limit, and never shows an unknown cost as zero", async () => {
    mockFetch(() => jsonResponse(200, USAGE));
    renderPage("/usage", <UsagePage />);

    expect(await screen.findByText("Free plan")).toBeInTheDocument();
    expect(screen.getByRole("meter", { name: "AI requests" })).toHaveAttribute("aria-valuenow", "300");
    expect(screen.getByText("Limit reached for this month.")).toBeInTheDocument();
    expect(screen.getByText("Over 80% used.")).toBeInTheDocument();
    expect(screen.getAllByText("Not configured").length).toBeGreaterThan(0);
    expect(screen.queryByText("$0.0000")).not.toBeInTheDocument();
    expect(screen.getByText("Current")).toBeInTheDocument();
  });
});

describe("audit log", () => {
  it("lists events with the actor, or System for automatic actions", async () => {
    mockFetch(() =>
      jsonResponse(200, {
        items: [
          {
            id: "a1",
            actor_user_id: null,
            actor_name: null,
            action: "CONVERSATIONS_PURGED",
            resource_type: "workspace",
            resource_id: "w1",
            metadata: { count: "3" },
            created_at: "2026-11-17T10:00:00Z",
          },
          {
            id: "a2",
            actor_user_id: "u1",
            actor_name: "Owner",
            action: "TICKET_RESOLVED",
            resource_type: "ticket",
            resource_id: "t1",
            metadata: { outcome: "SOLVED" },
            created_at: "2026-11-17T09:00:00Z",
          },
        ],
        page: 0,
        size: 50,
        total: 2,
      }),
    );
    renderPage("/audit", <AuditLogPage />);
    expect(await screen.findByText("Conversations purged")).toBeInTheDocument();
    expect(screen.getByText("System")).toBeInTheDocument();
    expect(screen.getByText("outcome: SOLVED")).toBeInTheDocument();
  });
});

describe("data and privacy settings", () => {
  it("lets an owner set a retention period within range", async () => {
    const { calls } = mockFetch(() => jsonResponse(200, { conversation_retention_days: 90 }));
    const user = userEvent.setup();
    renderPage("/settings", <SettingsPage />);

    const field = screen.getByLabelText("Delete conversations after (days)");
    const save = screen.getByRole("button", { name: "Save retention period" });
    await user.type(field, "7");
    expect(save).toBeDisabled();
    await user.clear(field);
    await user.type(field, "90");
    await user.click(save);
    await waitFor(() => expect(calls.some((c) => c.url.endsWith("/retention"))).toBe(true));
    expect(JSON.parse(calls.find((c) => c.url.endsWith("/retention"))!.init.body as string)).toEqual({
      conversation_retention_days: 90,
    });
    expect(await screen.findByText("Retention period saved.")).toBeInTheDocument();
  });

  it("does not offer data controls to an admin", () => {
    mockFetch(() => jsonResponse(200, {}));
    renderPage("/settings", <SettingsPage />, "ADMIN");
    expect(screen.queryByText("Data and privacy")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Download export" })).not.toBeInTheDocument();
  });
});

describe("suspension and platform admin", () => {
  it("shows a suspended workspace in the list with its reason, without a link", async () => {
    mockFetch((url) => {
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse(200, SESSION);
      }
      if (url.endsWith("/platform/access")) {
        return jsonResponse(200, { platform_admin: false });
      }
      return jsonResponse(200, [
        { id: "w1", name: "TechStore", slug: "techstore", role: "OWNER", status: "SUSPENDED", suspended_reason: "Unpaid invoice" },
      ]);
    });
    renderApp("/");
    expect(await screen.findByText(/This workspace is suspended: Unpaid invoice/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /TechStore/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Platform admin" })).not.toBeInTheDocument();
  });

  it("explains a suspended workspace instead of a permission error", async () => {
    mockFetch((url) => {
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse(200, SESSION);
      }
      if (url.endsWith("/workspaces/w1")) {
        return errorResponse(403, "WORKSPACE_SUSPENDED", "This workspace is suspended.");
      }
      return jsonResponse(200, []);
    });
    renderApp("/w/w1/overview");
    expect(await screen.findByRole("alert")).toHaveTextContent(/suspended/i);
  });

  it("shows the platform page as missing to someone who is not a platform admin", async () => {
    mockFetch((url) => {
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse(200, SESSION);
      }
      return errorResponse(404, "NOT_FOUND", "The resource was not found.");
    });
    renderApp("/platform");
    expect(await screen.findByText("This page does not exist.")).toBeInTheDocument();
  });

  it("lets a platform admin suspend a workspace with a reason", async () => {
    const tenant = {
      id: "w1",
      name: "TechStore",
      slug: "techstore",
      status: "ACTIVE",
      suspended_reason: null,
      plan_code: "FREE",
      ai_requests_this_month: 12,
      conversations_this_month: 4,
      created_at: "2026-11-01T10:00:00Z",
    };
    const { calls } = mockFetch((url) => {
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse(200, SESSION);
      }
      if (url.includes("/platform/tenants?")) {
        return jsonResponse(200, { items: [tenant], page: 0, size: 100, total: 1 });
      }
      if (url.includes("/platform/audit-events")) {
        return jsonResponse(200, { items: [], page: 0, size: 20, total: 0 });
      }
      return jsonResponse(200, { ...tenant, status: "SUSPENDED" });
    });
    const user = userEvent.setup();
    renderApp("/platform");

    expect(await screen.findByText(/12 AI requests, 4/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Suspend" }));
    const confirm = screen.getByRole("button", { name: "Confirm suspension" });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText(/Reason/), "Unpaid invoice");
    await user.click(confirm);
    await waitFor(() => expect(calls.some((c) => c.url.endsWith("/platform/tenants/w1/suspend"))).toBe(true));
    expect(JSON.parse(calls.find((c) => c.url.endsWith("/suspend"))!.init.body as string)).toEqual({ reason: "Unpaid invoice" });
  });
});
