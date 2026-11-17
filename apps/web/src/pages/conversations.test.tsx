import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Outlet, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import { canViewConversations } from "../lib/permissions";
import type { ConversationSummary, ThreadMessage, Workspace, WorkspaceRole } from "../lib/types";
import { jsonResponse, mockFetch } from "../test/helpers";
import { ConversationPage, ConversationsPage } from "./ConversationsPage";
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
  created_at: "2026-11-17T10:00:00Z",
};

function summary(overrides: Partial<ConversationSummary>): ConversationSummary {
  return {
    id: "c1",
    status: "OPEN",
    control_mode: "AI_ACTIVE",
    origin: "https://shop.example.com",
    created_at: "2026-11-17T10:00:00Z",
    last_message_at: "2026-11-17T10:05:00Z",
    handoff_requested_at: null,
    message_count: 2,
    last_message_preview: "Refunds are accepted within 30 days.",
    ...overrides,
  };
}

function renderAt(path: string, role: WorkspaceRole = "OWNER") {
  const context: WorkspaceOutletContext = {
    workspace: { ...WORKSPACE, role },
    user: { id: "u1", email: "me@example.test", display_name: "Me" },
    reloadWorkspace: () => {},
  };
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Outlet context={context} />}>
          <Route path="/conversations" element={<ConversationsPage />} />
          <Route path="/conversations/:conversationId" element={<ConversationPage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe("conversations", () => {
  it("lists conversations with their state and pages through them", async () => {
    const { calls } = mockFetch((url) =>
      jsonResponse(200, {
        items: [summary({}), summary({ id: "c2", control_mode: "HUMAN_PENDING", message_count: 1 })],
        page: url.includes("page=1") ? 1 : 0,
        size: 20,
        total: 45,
      }),
    );
    const user = userEvent.setup();
    renderAt("/conversations");

    expect(await screen.findByText("Waiting for a person")).toBeInTheDocument();
    expect(screen.getByText("Assistant replying")).toBeInTheDocument();
    expect(screen.getAllByText("https://shop.example.com")).toHaveLength(2);
    expect(screen.getByText("1 message")).toBeInTheDocument();
    expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Next" }));

    expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
    expect(calls.at(-1)!.url).toBe("/api/v1/workspaces/w1/conversations?page=1&size=20");
  });

  it("shows a thread with citations and marks replies the customer never saw", async () => {
    const messages: ThreadMessage[] = [
      {
        id: "m1",
        sender_type: "CUSTOMER",
        visibility: "PUBLIC",
        delivery_status: "DELIVERED",
        body: "Can I return my laptop?",
        created_at: "2026-11-17T10:00:00Z",
        outcome: null,
        citations: [],
      },
      {
        id: "m2",
        sender_type: "ASSISTANT",
        visibility: "PUBLIC",
        delivery_status: "DELIVERED",
        body: "Yes, within 30 days.",
        created_at: "2026-11-17T10:00:05Z",
        outcome: "ANSWERED",
        citations: [{ chunk_id: "k1", document_id: "d1", filename: "returns.pdf", page_number: 3, heading: null, snippet: "Within 30 days." }],
      },
      {
        id: "m3",
        sender_type: "ASSISTANT",
        visibility: "INTERNAL",
        delivery_status: "SUPPRESSED",
        body: "A withheld reply.",
        created_at: "2026-11-17T10:01:00Z",
        outcome: "ANSWERED",
        citations: [],
      },
    ];
    mockFetch((url) =>
      url.includes("/messages")
        ? jsonResponse(200, { items: messages, page: 0, size: 50, total: 3 })
        : jsonResponse(200, summary({ handoff_requested_at: "2026-11-17T10:02:00Z" })),
    );
    renderAt("/conversations/c1");

    expect(await screen.findByText("Yes, within 30 days.")).toBeInTheDocument();
    expect(screen.getByText(/returns\.pdf, page 3: Within 30 days\./)).toBeInTheDocument();
    expect(screen.getByText(/Not shown to the customer/)).toBeInTheDocument();
    expect(await screen.findByText(/asked for a person/)).toBeInTheDocument();
    expect(screen.getByText(/read-only/)).toBeInTheDocument();
  });

  it("is not available to agents", () => {
    const { calls } = mockFetch(() => jsonResponse(200, {}));
    renderAt("/conversations", "AGENT");

    expect(screen.getByRole("alert")).toHaveTextContent(/do not have permission/);
    expect(canViewConversations("SUPERVISOR")).toBe(true);
    expect(canViewConversations("AGENT")).toBe(false);
    expect(calls.length).toBeLessThanOrEqual(1);
  });
});
