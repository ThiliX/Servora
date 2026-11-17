import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Outlet, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import { NotificationsBell } from "../components/NotificationsBell";
import type { ThreadMessage, TicketDetail, TicketSummary, Workspace, WorkspaceRole } from "../lib/types";
import { jsonResponse, mockFetch } from "../test/helpers";
import { controlLabel, InboxPage } from "./InboxPage";
import type { WorkspaceOutletContext } from "./WorkspaceLayout";

const WORKSPACE: Workspace = {
  id: "w1",
  name: "TechStore",
  slug: "techstore",
  role: "AGENT",
  status: "ACTIVE",
  timezone: "UTC",
  default_language: "en",
  industry: null,
  logo_url: null,
  created_at: "2026-11-17T10:00:00Z",
};

function ticket(overrides: Partial<TicketSummary> = {}): TicketSummary {
  return {
    id: "t1",
    number: 7,
    status: "OPEN",
    priority: "HIGH",
    reason: "CUSTOMER_REQUEST",
    assignee_membership_id: null,
    assignee_name: null,
    conversation_id: "c1",
    control_mode: "ESCALATION_PENDING",
    conversation_status: "OPEN",
    conversation_version: 2,
    origin: "https://shop.example.com",
    resolution_outcome: null,
    created_at: "2026-11-17T10:00:00Z",
    updated_at: "2026-11-17T10:00:00Z",
    last_message_at: "2026-11-17T10:01:00Z",
    last_message_preview: "I want a refund",
    ...overrides,
  };
}

function detail(overrides: Partial<TicketDetail> = {}, summary: Partial<TicketSummary> = {}): TicketDetail {
  return { ticket: ticket(summary), events: [], feedback: null, can_work: false, assigned_to_me: false, ...overrides };
}

const MESSAGES: ThreadMessage[] = [
  {
    id: "m1",
    sender_type: "CUSTOMER",
    visibility: "PUBLIC",
    delivery_status: "DELIVERED",
    body: "I want a refund",
    created_at: "2026-11-17T10:01:00Z",
    outcome: null,
    citations: [],
  },
  {
    id: "m2",
    sender_type: "ASSISTANT",
    visibility: "AI_SUGGESTION",
    delivery_status: "PENDING_APPROVAL",
    body: "Refunds are accepted within 30 days.",
    created_at: "2026-11-17T10:03:00Z",
    outcome: "ANSWERED",
    citations: [{ chunk_id: "k", document_id: "d", filename: "refunds.md", page_number: null, heading: null, snippet: "" }],
  },
];

function renderInbox(path: string, role: WorkspaceRole = "AGENT") {
  const context: WorkspaceOutletContext = {
    workspace: { ...WORKSPACE, role },
    user: { id: "u1", email: "agent@example.test", display_name: "Sam" },
    reloadWorkspace: () => {},
  };
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<Outlet context={context} />}>
          <Route path="/w/w1/inbox" element={<InboxPage />} />
          <Route path="/w/w1/inbox/:ticketId" element={<InboxPage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

function api(current: () => TicketDetail) {
  return mockFetch((url, init) => {
    if (url.includes("/tickets?")) {
      return jsonResponse(200, { items: [current().ticket], page: 0, size: 50, total: 1 });
    }
    if (url.endsWith("/takeover")) {
      return jsonResponse(200, detail({ can_work: true, assigned_to_me: true }, { control_mode: "HUMAN_ACTIVE", status: "IN_PROGRESS", assignee_name: "Sam" }));
    }
    if (url.endsWith("/resolve")) {
      return jsonResponse(200, detail({ can_work: true, assigned_to_me: true }, { status: "RESOLVED", conversation_status: "CLOSED", resolution_outcome: "SOLVED" }));
    }
    if (url.includes("/messages")) {
      return jsonResponse(200, { items: MESSAGES, page: 0, size: 100, total: 2 });
    }
    if (url.endsWith("/notes") && init.method === "POST") {
      return jsonResponse(201, { id: "n2", author_name: "Sam", body: "x", created_at: "2026-11-17T10:04:00Z" });
    }
    if (url.endsWith("/notes")) {
      return jsonResponse(200, [{ id: "n1", author_name: "Alex", body: "VIP customer", created_at: "2026-11-17T10:02:00Z" }]);
    }
    if (url.endsWith("/replies")) {
      return jsonResponse(201, { id: "m3", created_at: "2026-11-17T10:05:00Z" });
    }
    return jsonResponse(200, current());
  });
}

describe("agent inbox", () => {
  it("shows the queue, the thread with notes and drafts, and who is replying", async () => {
    api(() => detail());
    renderInbox("/w/w1/inbox/t1");

    const queue = await screen.findByRole("region", { name: "Ticket queue" });
    expect(await within(queue).findByText("#7")).toBeInTheDocument();
    expect(await screen.findByText("VIP customer")).toBeInTheDocument();
    expect(screen.getByText(/Internal note · Alex/)).toBeInTheDocument();
    expect(screen.getByText("AI draft (not sent)")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Waiting for a person to take over");
    // Replying is disabled until the agent takes over.
    expect(screen.getByLabelText("Reply to the customer")).toBeDisabled();
    expect(screen.getByText("Take over the conversation to reply.")).toBeInTheDocument();
    expect(screen.queryByLabelText("Assign to")).not.toBeInTheDocument();
  });

  it("takes over with the version it saw, then replies using an AI draft", async () => {
    let current = detail();
    const { calls } = api(() => current);
    const user = userEvent.setup();
    renderInbox("/w/w1/inbox/t1");

    const takeoverButton = await screen.findByRole("button", { name: "Take over" });
    // From now on the server reports the taken-over state, as it would after the takeover.
    current = detail({ can_work: true, assigned_to_me: true }, { control_mode: "HUMAN_ACTIVE", status: "IN_PROGRESS", assignee_name: "Sam" });
    await user.click(takeoverButton);
    const takeover = calls.find((c) => c.url.endsWith("/takeover"))!;
    expect(JSON.parse(takeover.init.body as string)).toEqual({ expected_version: 2 });
    expect(await screen.findByText("You are replying")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Take over" })).not.toBeInTheDocument();

    await user.click(await screen.findByRole("button", { name: "Use this draft" }));
    expect(screen.getByLabelText("Reply to the customer")).toHaveValue("Refunds are accepted within 30 days.");
    await user.click(screen.getByRole("button", { name: "Send reply" }));

    await waitFor(() => expect(calls.some((c) => c.url.endsWith("/replies"))).toBe(true));
    const reply = calls.find((c) => c.url.endsWith("/replies"))!;
    expect(JSON.parse(reply.init.body as string)).toEqual({ body: "Refunds are accepted within 30 days.", suggestion_id: "m2" });
  });

  it("adds internal notes from the note composer even before taking over", async () => {
    const { calls } = api(() => detail());
    const user = userEvent.setup();
    renderInbox("/w/w1/inbox/t1");

    await user.click(await screen.findByRole("tab", { name: "Internal note" }));
    await user.type(screen.getByLabelText("Internal note, not shown to the customer"), "Checked the order");
    await user.click(screen.getByRole("button", { name: "Add note" }));

    await waitFor(() => expect(calls.some((c) => c.url.endsWith("/notes") && c.init.method === "POST")).toBe(true));
    const note = calls.find((c) => c.url.endsWith("/notes") && c.init.method === "POST")!;
    expect(JSON.parse(note.init.body as string)).toEqual({ body: "Checked the order" });
  });

  it("lets the person working on it resolve with an outcome", async () => {
    let current = detail({ can_work: true, assigned_to_me: true }, { control_mode: "HUMAN_ACTIVE", status: "IN_PROGRESS", assignee_name: "Sam" });
    const { calls } = api(() => current);
    const user = userEvent.setup();
    renderInbox("/w/w1/inbox/t1");

    await user.selectOptions(await screen.findByLabelText("Outcome"), "DUPLICATE");
    const resolve = screen.getByRole("button", { name: "Resolve" });
    // From now on the server reports the resolved state, as it would after resolving.
    current = detail(
      { can_work: true, assigned_to_me: true },
      { control_mode: "HUMAN_ACTIVE", status: "RESOLVED", conversation_status: "CLOSED", resolution_outcome: "DUPLICATE", assignee_name: "Sam" },
    );
    await user.click(resolve);

    await waitFor(() => expect(calls.some((c) => c.url.endsWith("/resolve"))).toBe(true));
    expect(JSON.parse(calls.find((c) => c.url.endsWith("/resolve"))!.init.body as string)).toEqual({ outcome: "DUPLICATE", note: "" });
    expect(await screen.findByText("This conversation has ended")).toBeInTheDocument();
  });

  it("describes who is replying", () => {
    expect(controlLabel("AI_ACTIVE", null, false)).toBe("The assistant is replying");
    expect(controlLabel("HUMAN_ACTIVE", "Alex", false)).toBe("Alex is replying");
    expect(controlLabel("HUMAN_ACTIVE", "Sam", true)).toBe("You are replying");
  });
});

describe("notifications bell", () => {
  it("shows the unread count and links to the ticket", async () => {
    const { calls } = mockFetch((url) =>
      url.endsWith("/read")
        ? jsonResponse(200, { unread_count: 0, items: [] })
        : jsonResponse(200, {
            unread_count: 1,
            items: [{ id: "x", type: "TICKET_ASSIGNED", ticket_id: "t1", ticket_number: 7, conversation_id: "c1", created_at: "2026-11-17T10:00:00Z", read: false }],
          }),
    );
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <NotificationsBell workspaceId="w1" />
      </MemoryRouter>,
    );

    await user.click(await screen.findByRole("button", { name: "Notifications (1)" }));
    expect(screen.getByRole("link", { name: "Ticket #7: assigned to you" })).toHaveAttribute("href", "/w/w1/inbox/t1");
    await user.click(screen.getByRole("button", { name: "Mark all as read" }));
    await waitFor(() => expect(calls.some((c) => c.url.endsWith("/notifications/read"))).toBe(true));
  });
});
