import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { Button, Card, Empty, ErrorState, FormError, Loading, Notice, PageHeader } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import { canManageData, canViewConversations } from "../lib/permissions";
import type { ControlMode, ConversationSummary, Page, ThreadMessage } from "../lib/types";
import { toApiError, useAsync } from "../lib/useAsync";
import { useWorkspace } from "./WorkspaceLayout";

const PAGE_SIZE = 20;
const MESSAGE_PAGE_SIZE = 50;

const MODE_LABELS: Record<ControlMode, string> = {
  AI_ACTIVE: "Assistant replying",
  ESCALATION_PENDING: "Waiting for a person",
  HUMAN_ACTIVE: "Person replying",
};

const SENDER_LABELS: Record<ThreadMessage["sender_type"], string> = {
  CUSTOMER: "Customer",
  ASSISTANT: "Assistant",
  AGENT: "Team",
  SYSTEM: "Notice",
};

export function formatTime(iso: string | null): string {
  if (!iso) {
    return "";
  }
  return new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (page: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / size));
  if (pages <= 1) {
    return null;
  }
  return (
    <nav aria-label="Pages" className="mt-4 flex items-center gap-3 text-sm">
      <Button variant="secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Previous
      </Button>
      <span>
        Page {page + 1} of {pages}
      </span>
      <Button variant="secondary" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>
        Next
      </Button>
    </nav>
  );
}

export function ConversationsPage() {
  const { workspace } = useWorkspace();
  const [page, setPage] = useState(0);
  const path = `/workspaces/${workspace.id}/conversations?page=${page}&size=${PAGE_SIZE}`;
  const { state, reload } = useAsync(() => api<Page<ConversationSummary>>(path), path);

  if (!canViewConversations(workspace.role)) {
    return <Notice tone="error">You do not have permission to view conversations.</Notice>;
  }
  return (
    <>
      <PageHeader title="Conversations" description="Every conversation with your customers, including those the assistant handled alone." />
      <Card>
        {state.status === "loading" && <Loading label="Loading conversations" />}
        {state.status === "error" && <ErrorState error={state.error} onRetry={reload} />}
        {state.status === "ready" && state.data.items.length === 0 && (
          <Empty>No conversations yet. They appear here when customers chat through your website widget.</Empty>
        )}
        {state.status === "ready" && state.data.items.length > 0 && (
          <>
            <ul className="divide-y divide-line">
              {state.data.items.map((conversation) => (
                <li key={conversation.id} className="py-3">
                  <Link to={conversation.id} className="block rounded-md hover:bg-subtle focus-visible:outline-2">
                    <p className="flex flex-wrap items-center gap-2 text-sm">
                      <span className="font-medium">{conversation.origin}</span>
                      <span className="rounded-full bg-subtle px-2 py-0.5 text-xs text-fg">
                        {MODE_LABELS[conversation.control_mode]}
                      </span>
                      <span className="text-fg-subtle">{formatTime(conversation.last_message_at)}</span>
                    </p>
                    <p className="mt-1 truncate text-sm text-fg-muted">{conversation.last_message_preview ?? ""}</p>
                    <p className="text-xs text-fg-subtle">
                      {conversation.message_count} {conversation.message_count === 1 ? "message" : "messages"}
                    </p>
                  </Link>
                </li>
              ))}
            </ul>
            <Pager page={state.data.page} size={state.data.size} total={state.data.total} onPage={setPage} />
          </>
        )}
      </Card>
    </>
  );
}

export function ConversationPage() {
  const { workspace } = useWorkspace();
  const { conversationId = "" } = useParams();
  const [page, setPage] = useState(0);
  const base = `/workspaces/${workspace.id}/conversations/${conversationId}`;
  const summary = useAsync(() => api<ConversationSummary>(base), base);
  const messagesPath = `${base}/messages?page=${page}&size=${MESSAGE_PAGE_SIZE}`;
  const messages = useAsync(() => api<Page<ThreadMessage>>(messagesPath), messagesPath);

  if (!canViewConversations(workspace.role)) {
    return <Notice tone="error">You do not have permission to view conversations.</Notice>;
  }
  return (
    <>
      <p>
        <Link to=".." relative="path" className="text-sm text-accent-text underline">
          All conversations
        </Link>
      </p>
      <PageHeader title="Conversation" />
      {summary.state.status === "error" && <ErrorState error={summary.state.error} onRetry={summary.reload} />}
      {summary.state.status === "ready" && (
        <p className="text-sm text-fg-muted">
          From {summary.state.data.origin} · started {formatTime(summary.state.data.created_at)} ·{" "}
          {MODE_LABELS[summary.state.data.control_mode]}
          {summary.state.data.handoff_requested_at &&
            ` · the customer asked for a person ${formatTime(summary.state.data.handoff_requested_at)}`}
        </p>
      )}
      <Notice tone="info">
        This is the full record, including replies withheld from the customer. To reply, open the ticket in the{" "}
        <Link to="../../inbox" relative="path" className="underline">
          Inbox
        </Link>
        .
      </Notice>
      <Card>
        {messages.state.status === "loading" && <Loading label="Loading messages" />}
        {messages.state.status === "error" && <ErrorState error={messages.state.error} onRetry={messages.reload} />}
        {messages.state.status === "ready" && (
          <>
            <ol className="space-y-3" aria-label="Messages">
              {messages.state.data.items.map((message) => (
                <li
                  key={message.id}
                  className={`rounded-md border p-3 text-sm ${
                    message.delivery_status === "SUPPRESSED" ? "border-dashed border-warning-line bg-warning-soft" : "border-line"
                  }`}
                >
                  <p className="flex flex-wrap gap-2 text-xs text-fg-subtle">
                    <span className="font-semibold text-fg">{SENDER_LABELS[message.sender_type]}</span>
                    <span>{formatTime(message.created_at)}</span>
                    {message.delivery_status === "SUPPRESSED" && (
                      <span className="font-medium text-warning-text">Not shown to the customer: a person took over</span>
                    )}
                    {message.outcome && message.outcome !== "ANSWERED" && <span>Outcome: {message.outcome}</span>}
                  </p>
                  <p className="mt-1 whitespace-pre-wrap">{message.body}</p>
                  {message.citations.length > 0 && (
                    <ul className="mt-2 list-disc pl-5 text-xs text-fg-muted">
                      {message.citations.map((citation) => (
                        <li key={citation.chunk_id}>
                          {citation.filename}
                          {citation.page_number !== null && `, page ${citation.page_number}`}: {citation.snippet}
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              ))}
            </ol>
            <Pager
              page={messages.state.data.page}
              size={messages.state.data.size}
              total={messages.state.data.total}
              onPage={setPage}
            />
          </>
        )}
      </Card>
      {canManageData(workspace.role) && <DeleteConversation workspaceId={workspace.id} conversationId={conversationId} />}
    </>
  );
}

/** Owners can delete a conversation permanently, for example on a customer's erasure request. */
function DeleteConversation({ workspaceId, conversationId }: { workspaceId: string; conversationId: string }) {
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await api(`/workspaces/${workspaceId}/conversations/${conversationId}`, { method: "DELETE" });
      navigate("..", { relative: "path", replace: true });
    } catch (caught) {
      setError(toApiError(caught));
      setBusy(false);
    }
  }

  return (
    <Card title="Delete this conversation">
      <p className="mb-3 text-sm text-fg-muted">
        Deletes the messages, ticket, internal notes, rating and AI records of this conversation. This cannot be undone.
        Usage counts are kept.
      </p>
      <FormError error={error} />
      {confirming ? (
        <div className="flex gap-2">
          <Button variant="danger" disabled={busy} onClick={() => void remove()}>
            {busy ? "Deleting…" : "Delete permanently"}
          </Button>
          <Button variant="secondary" disabled={busy} onClick={() => setConfirming(false)}>
            Cancel
          </Button>
        </div>
      ) : (
        <Button variant="danger" onClick={() => setConfirming(true)}>
          Delete conversation
        </Button>
      )}
    </Card>
  );
}
