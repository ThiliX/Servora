import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Outlet, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import type { PlaygroundResult, Workspace, WorkspaceRole } from "../lib/types";
import { errorResponse, jsonResponse, mockFetch } from "../test/helpers";
import { formatScore, PlaygroundPage } from "./PlaygroundPage";
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

const ANSWERED: PlaygroundResult = {
  id: "i1",
  outcome: "ANSWERED",
  answer: "Refunds are accepted within 30 days.",
  intent: "question",
  needs_escalation: false,
  escalation_reason: null,
  citations: [
    {
      chunk_id: "c1",
      document_id: "d1",
      filename: "refund-policy.pdf",
      page_number: 2,
      heading: null,
      snippet: "Refunds are accepted within 30 days of purchase.",
    },
  ],
  retrieval: [
    { chunk_id: "c1", document_id: "d1", filename: "refund-policy.pdf", score: 0.8123, used: true },
    { chunk_id: "c2", document_id: "d2", filename: "shipping.md", score: 0.21, used: false },
  ],
  provider: "anthropic",
  model: "claude-opus-5-5",
  prompt_version: "2026-11-17.1",
  evidence_threshold: 0.45,
  latency_ms: 1840,
  input_tokens: 900,
  output_tokens: 60,
};

function renderPage(role: WorkspaceRole = "OWNER") {
  const context: WorkspaceOutletContext = {
    workspace: { ...WORKSPACE, role },
    user: { id: "user-1", email: "me@example.test", display_name: "Me" },
    reloadWorkspace: () => {},
  };
  return render(
    <MemoryRouter initialEntries={["/page"]}>
      <Routes>
        <Route element={<Outlet context={context} />}>
          <Route path="/page" element={<PlaygroundPage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

async function ask(question: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Ask a question as a customer would"), question);
  await user.click(screen.getByRole("button", { name: "Ask" }));
}

describe("assistant playground", () => {
  it("shows the answer, its sources and what was retrieved", async () => {
    const { calls } = mockFetch(() => jsonResponse(200, ANSWERED));
    renderPage();

    await ask("  What is the refund policy?  ");

    expect(await screen.findByText("Refunds are accepted within 30 days.")).toBeInTheDocument();
    expect(screen.getByText("Answered")).toBeInTheDocument();
    expect(calls[0]!.url).toBe("/api/v1/workspaces/w1/assistant/test");
    expect(JSON.parse(calls[0]!.init.body as string)).toEqual({ question: "What is the refund policy?" });

    expect(screen.getByText("page 2")).toBeInTheDocument();
    expect(screen.getByText("Refunds are accepted within 30 days of purchase.")).toBeInTheDocument();
    const rows = screen.getAllByRole("row");
    expect(within(rows[1]!).getByText("81%")).toBeInTheDocument();
    expect(within(rows[1]!).getByText("Yes")).toBeInTheDocument();
    expect(within(rows[2]!).getByText("No, too weak")).toBeInTheDocument();
    expect(screen.getByText(/at least 45%/)).toBeInTheDocument();
    expect(screen.getByText(/claude-opus-5-5 · 1840 ms · 960 tokens/)).toBeInTheDocument();
    expect(screen.queryByText(/built-in test model/)).not.toBeInTheDocument();
  });

  it("says so when the reply came from the built-in test model", async () => {
    mockFetch(() => jsonResponse(200, { ...ANSWERED, provider: "fake", model: "servora-fake-llm" }));
    renderPage();

    await ask("What is the refund policy?");

    expect(await screen.findByText(/built-in test model/)).toBeInTheDocument();
  });

  it("explains an abstention in plain words and never shows the raw outcome code", async () => {
    mockFetch(() =>
      jsonResponse(200, {
        ...ANSWERED,
        outcome: "NO_EVIDENCE",
        answer: "Let me get a colleague.",
        needs_escalation: true,
        citations: [],
        retrieval: [],
        provider: "fake",
        model: null,
      }),
    );
    renderPage();

    await ask("Do you sell helmets?");

    expect(await screen.findByText("No answer found")).toBeInTheDocument();
    expect(screen.getByText("Let me get a colleague.")).toBeInTheDocument();
    expect(screen.getByText(/instead of guessing/)).toBeInTheDocument();
    expect(screen.getByText("No sources were cited.")).toBeInTheDocument();
    expect(screen.getByText(/No indexed documents matched/)).toBeInTheDocument();
    // The test model was never called, so its notice would be misleading here.
    expect(screen.queryByText(/built-in test model/)).not.toBeInTheDocument();
    expect(screen.getByText(/Model: not called/)).toBeInTheDocument();
    expect(screen.queryByText("NO_EVIDENCE")).not.toBeInTheDocument();
  });

  it("shows a rate-limit error and keeps the question", async () => {
    mockFetch(() => errorResponse(429, "PLAYGROUND_RATE_LIMITED", "Too many test questions this hour."));
    renderPage();

    await ask("Hello?");

    expect(await screen.findByRole("alert")).toHaveTextContent("Too many test questions");
    expect(screen.getByLabelText("Ask a question as a customer would")).toHaveValue("Hello?");
  });

  it("cannot be submitted empty and is not available to agents", () => {
    const { calls } = mockFetch(() => jsonResponse(200, ANSWERED));
    const { unmount } = renderPage();
    expect(screen.getByRole("button", { name: "Ask" })).toBeDisabled();
    unmount();

    renderPage("AGENT");
    expect(screen.getByRole("alert")).toHaveTextContent(/do not have permission/);
    expect(screen.queryByRole("button", { name: "Ask" })).not.toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it("formats similarity as a percentage", () => {
    expect(formatScore(0.8123)).toBe("81%");
    expect(formatScore(-0.2)).toBe("0%");
    expect(formatScore(1.2)).toBe("100%");
  });
});
