import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Outlet, Route, Routes } from "react-router";
import { describe, expect, it, vi } from "vitest";
import type { KnowledgeDocument, Widget, Workspace, WorkspaceRole } from "../lib/types";
import { errorResponse, jsonResponse, mockFetch } from "../test/helpers";
import { AssistantPage } from "./AssistantPage";
import { failureReason, formatSize, KnowledgePage } from "./KnowledgePage";
import { embedSnippet, parseOrigins, WidgetPage } from "./WidgetPage";
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
  created_at: "2026-11-16T10:00:00Z",
};

function doc(overrides: Partial<KnowledgeDocument>): KnowledgeDocument {
  return {
    id: "d1",
    lineage_id: "d1",
    version: 1,
    filename: "refund-policy.pdf",
    mime: "application/pdf",
    size_bytes: 2048,
    status: "READY",
    error_code: null,
    chunk_count: 4,
    created_at: "2026-11-16T10:00:00Z",
    processed_at: "2026-11-16T10:00:05Z",
    ...overrides,
  };
}

const WIDGET: Widget = {
  id: "wid1",
  name: "Website widget",
  public_key: "wk_abc123",
  loader_url: "http://localhost:8001/servora-widget.js",
  status: "ACTIVE",
  theme_color: "#4f46e5",
  position: "BOTTOM_RIGHT",
  title: "TechStore",
  show_branding: true,
  allowed_origins: ["https://shop.example.com"],
  updated_at: "2026-11-16T10:00:00Z",
};

function renderPage(page: React.ReactNode, role: WorkspaceRole = "OWNER") {
  const context: WorkspaceOutletContext = {
    workspace: { ...WORKSPACE, role },
    user: { id: "user-1", email: "me@example.test", display_name: "Me" },
    reloadWorkspace: () => {},
  };
  return render(
    <MemoryRouter initialEntries={["/page"]}>
      <Routes>
        <Route element={<Outlet context={context} />}>
          <Route path="/page" element={page} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

function documentsPage(items: KnowledgeDocument[]): Response {
  return jsonResponse(200, { items, page: 0, size: 100, total: items.length });
}

describe("knowledge base page", () => {
  it("shows each document's state and explains failures in plain words", async () => {
    mockFetch(() =>
      documentsPage([
        doc({ id: "d1" }),
        doc({ id: "d2", filename: "scan.pdf", status: "FAILED", error_code: "EMPTY_DOCUMENT", chunk_count: 0 }),
        doc({ id: "d3", filename: "old.txt", status: "ARCHIVED", version: 2 }),
      ]),
    );

    renderPage(<KnowledgePage />);

    expect(await screen.findByText("refund-policy.pdf")).toBeInTheDocument();
    expect(screen.getByText(/4 sections indexed/)).toBeInTheDocument();
    expect(screen.getByText(/Scanned PDFs without a text layer/)).toBeInTheDocument();
    expect(screen.queryByText("EMPTY_DOCUMENT")).not.toBeInTheDocument();
    expect(screen.getByText("version 2")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Restore" })).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Archive" })).toHaveLength(1);
  });

  it("offers no lifecycle actions except delete while a document is being processed", async () => {
    mockFetch(() => documentsPage([doc({ status: "PROCESSING", chunk_count: 0 })]));

    renderPage(<KnowledgePage />);

    expect(await screen.findByText("Processing")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Archive" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Replace" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete" })).toBeInTheDocument();
  });

  it("uploads the chosen file as multipart form data and shows server rejections", async () => {
    const { calls } = mockFetch((_url, init) =>
      init.method === "POST"
        ? errorResponse(400, "CONTENT_MISMATCH", "The file content does not match its type.")
        : documentsPage([]),
    );
    const user = userEvent.setup();
    renderPage(<KnowledgePage />);
    await screen.findByText(/No documents yet/);

    const file = new File(["not really a pdf"], "fake.pdf", { type: "application/pdf" });
    await user.upload(screen.getByLabelText("Choose a document to upload"), file);

    expect(await screen.findByRole("alert")).toHaveTextContent("does not match its type");
    const upload = calls.find((call) => call.init.method === "POST")!;
    expect(upload.url).toBe("/api/v1/workspaces/w1/knowledge/documents");
    expect(upload.init.body).toBeInstanceOf(FormData);
    expect((upload.init.body as FormData).get("file")).toBeInstanceOf(File);
    // The browser must set the multipart boundary itself.
    expect((upload.init.headers as Record<string, string>)["Content-Type"]).toBeUndefined();
  });

  it("asks before deleting and does nothing if the user cancels", async () => {
    const { calls } = mockFetch(() => documentsPage([doc({})]));
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    const user = userEvent.setup();
    renderPage(<KnowledgePage />);

    await user.click(await screen.findByRole("button", { name: "Delete" }));
    expect(confirm).toHaveBeenCalledOnce();
    expect(calls.some((call) => call.init.method === "DELETE")).toBe(false);

    confirm.mockReturnValue(true);
    await user.click(screen.getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(calls.some((call) => call.init.method === "DELETE")).toBe(true));
  });

  it("is not available to agents", async () => {
    const { calls } = mockFetch(() => documentsPage([]));

    renderPage(<KnowledgePage />, "AGENT");

    expect(await screen.findByRole("alert")).toHaveTextContent(/do not have permission/);
    expect(screen.queryByRole("button", { name: "Choose file" })).not.toBeInTheDocument();
    expect(calls.every((call) => call.init.method !== "POST")).toBe(true);
  });
});

describe("widget page", () => {
  it("shows the key and embed snippet and saves the origin list as the server canonicalised it", async () => {
    const { calls } = mockFetch((_url, init) =>
      init.method === "PUT"
        ? jsonResponse(200, { ...WIDGET, allowed_origins: ["http://localhost:5173", "https://shop.example.com"] })
        : jsonResponse(200, WIDGET),
    );
    const user = userEvent.setup();
    renderPage(<WidgetPage />);

    const origins = await screen.findByLabelText("Websites allowed to show this widget");
    expect(screen.getByText(/data-widget-key="wk_abc123"/)).toBeInTheDocument();
    await user.clear(origins);
    await user.type(origins, "HTTPS://Shop.Example.com/\n\n  http://localhost:5173  ");
    await user.click(screen.getByRole("button", { name: "Save allowed websites" }));

    expect(await screen.findByText("Allowed websites saved.")).toBeInTheDocument();
    const put = calls.find((call) => call.init.method === "PUT")!;
    expect(JSON.parse(put.init.body as string)).toEqual({
      origins: ["HTTPS://Shop.Example.com/", "http://localhost:5173"],
    });
    expect(origins).toHaveValue("http://localhost:5173\nhttps://shop.example.com");
  });

  it("shows why an origin was rejected", async () => {
    mockFetch((_url, init) =>
      init.method === "PUT"
        ? errorResponse(400, "INVALID_ORIGIN", "Origins must use https, except for localhost.")
        : jsonResponse(200, WIDGET),
    );
    const user = userEvent.setup();
    renderPage(<WidgetPage />);

    await user.click(await screen.findByRole("button", { name: "Save allowed websites" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Origins must use https");
  });
});

describe("assistant page", () => {
  it("saves the whole configuration and reports the new version", async () => {
    const assistant = {
      id: "a1",
      name: "TechStore Assistant",
      welcome_message: "Hi!",
      fallback_message: "Let me get a colleague.",
      language: "en",
      tone: "FRIENDLY",
      automation_enabled: true,
      confidence_policy: "STRICT",
      current_version: 1,
      updated_at: "2026-11-16T10:00:00Z",
    };
    const { calls } = mockFetch((_url, init) =>
      jsonResponse(200, init.method === "PUT" ? { ...assistant, current_version: 2 } : assistant),
    );
    const user = userEvent.setup();
    renderPage(<AssistantPage />);

    await user.selectOptions(await screen.findByLabelText("Tone"), "PROFESSIONAL");
    await user.click(screen.getByRole("button", { name: "Save assistant" }));

    expect(await screen.findByText("Saved as version 2.")).toBeInTheDocument();
    const put = calls.find((call) => call.init.method === "PUT")!;
    expect(JSON.parse(put.init.body as string)).toMatchObject({
      name: "TechStore Assistant",
      tone: "PROFESSIONAL",
      confidence_policy: "STRICT",
      automation_enabled: true,
    });
  });
});

describe("helpers", () => {
  it("never shows a raw error code as the failure reason", () => {
    expect(failureReason("ENCRYPTED_PDF")).toMatch(/password-protected/);
    expect(failureReason("SOMETHING_NEW")).toBe("Processing failed. Try again.");
    expect(failureReason(null)).toBe("Processing failed. Try again.");
  });

  it("formats file sizes", () => {
    expect(formatSize(512)).toBe("512 B");
    expect(formatSize(2048)).toBe("2.0 KB");
    expect(formatSize(5 * 1024 * 1024)).toBe("5.0 MB");
  });

  it("parses one origin per line and leaves validation to the server", () => {
    expect(parseOrigins(" https://a.example.com \n\n\nhttps://b.example.com\n")).toEqual([
      "https://a.example.com",
      "https://b.example.com",
    ]);
  });

  it("builds the embed snippet from the public key", () => {
    const snippet = embedSnippet("https://widget.example.test/servora-widget.js", "wk_x");
    expect(snippet).toContain('data-widget-key="wk_x"');
    expect(snippet).toContain('src="https://widget.example.test/servora-widget.js"');
  });
});
