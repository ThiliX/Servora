import { useEffect, useRef, useState, type ChangeEvent } from "react";
import { Button, Card, Empty, ErrorState, FormError, Loading, Notice } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import { canConfigureAssistant } from "../lib/permissions";
import type { DocumentStatus, KnowledgeDocument, Page } from "../lib/types";
import { toApiError, useAsync } from "../lib/useAsync";
import { useWorkspace } from "./WorkspaceLayout";

const IN_FLIGHT: readonly DocumentStatus[] = ["UPLOADED", "QUEUED", "PROCESSING"];
const POLL_INTERVAL_MS = 3000;
const ACCEPT = ".pdf,.txt,.md,.markdown";

const STATUS_LABELS: Record<DocumentStatus, string> = {
  UPLOADED: "Waiting",
  QUEUED: "Queued",
  PROCESSING: "Processing",
  READY: "Ready",
  FAILED: "Failed",
  ARCHIVED: "Archived",
};

const STATUS_STYLES: Record<DocumentStatus, string> = {
  UPLOADED: "bg-slate-100 text-slate-700",
  QUEUED: "bg-slate-100 text-slate-700",
  PROCESSING: "bg-amber-100 text-amber-800",
  READY: "bg-emerald-100 text-emerald-800",
  FAILED: "bg-red-100 text-red-800",
  ARCHIVED: "bg-slate-200 text-slate-600",
};

const FAILURE_REASONS: Record<string, string> = {
  PARSE_ERROR: "The file could not be read. It may be damaged.",
  ENCRYPTED_PDF: "The PDF is password-protected. Upload an unprotected copy.",
  EMPTY_DOCUMENT: "No text was found. Scanned PDFs without a text layer are not supported.",
  TOO_MANY_PAGES: "The PDF has too many pages. Split it into smaller files.",
  TEXT_TOO_LARGE: "The document contains too much text. Split it into smaller files.",
  TOO_MANY_CHUNKS: "The document is too long. Split it into smaller files.",
  SOURCE_FILE_MISSING: "The stored file could not be found. Upload it again.",
  DISPATCH_FAILED: "Processing could not be started. Try again.",
  PROCESSING_UNAVAILABLE: "Processing was temporarily unavailable. Try again.",
  PROCESSING_TIMEOUT: "Processing took too long. Try a smaller file.",
};

/** Explains a failure code in plain words. Unknown codes get a generic message, never the raw code alone. */
export function failureReason(code: string | null): string {
  return (code && FAILURE_REASONS[code]) || "Processing failed. Try again.";
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function KnowledgePage() {
  const { workspace } = useWorkspace();
  const base = `/workspaces/${workspace.id}/knowledge/documents`;
  const { state, reload } = useAsync(() => api<Page<KnowledgeDocument>>(`${base}?size=100`), base);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const uploadInput = useRef<HTMLInputElement>(null);
  const replaceInput = useRef<HTMLInputElement>(null);
  const replaceTarget = useRef<string | null>(null);

  // Refresh while anything is still being processed, and stop once everything has settled.
  const processing = state.status === "ready" && state.data.items.some((doc) => IN_FLIGHT.includes(doc.status));
  useEffect(() => {
    if (!processing) {
      return;
    }
    const timer = window.setInterval(reload, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [processing, reload]);

  if (!canConfigureAssistant(workspace.role)) {
    return <Notice tone="error">You do not have permission to manage the knowledge base.</Notice>;
  }

  async function sendFile(path: string, file: File) {
    setUploading(true);
    setError(null);
    try {
      const form = new FormData();
      form.append("file", file);
      await api<KnowledgeDocument>(path, { method: "POST", form });
      reload();
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setUploading(false);
    }
  }

  function onUpload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (file) {
      void sendFile(base, file);
    }
  }

  function onReplace(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    const target = replaceTarget.current;
    event.target.value = "";
    if (file && target) {
      void sendFile(`${base}/${target}/replace`, file);
    }
  }

  async function act(doc: KnowledgeDocument, action: "archive" | "reprocess" | "delete") {
    if (action === "delete" && !window.confirm(`Delete "${doc.filename}"? This cannot be undone.`)) {
      return;
    }
    setBusyId(doc.id);
    setError(null);
    try {
      if (action === "delete") {
        await api(`${base}/${doc.id}`, { method: "DELETE" });
      } else {
        await api(`${base}/${doc.id}/${action}`, { method: "POST" });
      }
      reload();
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      <h1 className="text-xl font-bold">Knowledge base</h1>
      <Card title="Upload a document">
        <div className="space-y-3">
          <p className="text-sm text-slate-600">
            PDF, TXT or Markdown, up to 10 MB. Documents are stored privately and used only to answer your own
            customers.
          </p>
          <input ref={uploadInput} type="file" accept={ACCEPT} className="sr-only" aria-label="Choose a document to upload" onChange={onUpload} />
          <input ref={replaceInput} type="file" accept={ACCEPT} className="sr-only" aria-label="Choose a replacement file" onChange={onReplace} />
          <Button onClick={() => uploadInput.current?.click()} disabled={uploading}>
            {uploading ? "Uploading…" : "Choose file"}
          </Button>
          <FormError error={error} />
        </div>
      </Card>

      <Card title="Documents">
        {state.status === "loading" && <Loading label="Loading documents" />}
        {state.status === "error" && <ErrorState error={state.error} onRetry={reload} />}
        {state.status === "ready" && state.data.items.length === 0 && (
          <Empty>No documents yet. Upload your policies, FAQs or manuals to get started.</Empty>
        )}
        {state.status === "ready" && state.data.items.length > 0 && (
          <ul className="divide-y divide-slate-200">
            {state.data.items.map((doc) => {
              const settled = !IN_FLIGHT.includes(doc.status);
              const busy = busyId === doc.id;
              return (
                <li key={doc.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                  <div className="min-w-0">
                    <p className="truncate font-medium">
                      {doc.filename}
                      {doc.version > 1 && <span className="ml-2 text-xs text-slate-500">version {doc.version}</span>}
                    </p>
                    <p className="text-sm text-slate-500">
                      {formatSize(doc.size_bytes)}
                      {doc.status === "READY" && ` · ${doc.chunk_count} ${doc.chunk_count === 1 ? "section" : "sections"} indexed`}
                    </p>
                    {doc.status === "FAILED" && <p className="mt-1 text-sm text-red-700">{failureReason(doc.error_code)}</p>}
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[doc.status]}`}>
                      {STATUS_LABELS[doc.status]}
                    </span>
                    {settled && (
                      <>
                        {(doc.status === "FAILED" || doc.status === "ARCHIVED") && (
                          <Button variant="secondary" disabled={busy} onClick={() => void act(doc, "reprocess")}>
                            {doc.status === "ARCHIVED" ? "Restore" : "Retry"}
                          </Button>
                        )}
                        {doc.status === "READY" && (
                          <>
                            <Button
                              variant="secondary"
                              disabled={busy || uploading}
                              onClick={() => {
                                replaceTarget.current = doc.id;
                                replaceInput.current?.click();
                              }}
                            >
                              Replace
                            </Button>
                            <Button variant="secondary" disabled={busy} onClick={() => void act(doc, "archive")}>
                              Archive
                            </Button>
                          </>
                        )}
                      </>
                    )}
                    <Button variant="danger" disabled={busy} onClick={() => void act(doc, "delete")}>
                      Delete
                    </Button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </Card>
    </>
  );
}
