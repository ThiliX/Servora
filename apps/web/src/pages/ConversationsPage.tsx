import { useState } from "react";
import { Link, useParams } from "react-router";
import { Button, Card, Empty, ErrorState, Loading, Notice } from "../components/ui";
import { api } from "../lib/api";
import { canViewConversations } from "../lib/permissions";
import type { ControlMode, ConversationSummary, Page, ThreadMessage } from "../lib/types";
import { useAsync } from "../lib/useAsync";
import { useWorkspace } from "./WorkspaceLayout";

const PAGE_SIZE = 20;
const MESSAGE_PAGE_SIZE = 50;

const MODE_LABELS: Record<ControlMode, string> = {
  AI_ACTIVE: "Assistant replying",
  HUMAN_PENDING: "Waiting for a person",
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
      <h1 className="text-xl font-bold">Conversations</h1>
      <Card>
        {state.status === "loading" && <Loading label="Loading conversations" />}
        {state.status === "error" && <ErrorState error={state.error} onRetry={reload} />}
        {state.status === "ready" && state.data.items.length === 0 && (
          <Empty>No conversations yet. They appear here when customers chat through your website widget.</Empty>
        )}
        {state.status === "ready" && state.data.items.length > 0 && (
          <>
            <ul className="divide-y divide-slate-200">
              {state.data.items.map((conversation) => (
                <li key={conversation.id} className="py-3">
                  <Link to={conversation.id} className="block rounded-md hover:bg-slate-50 focus-visible:outline-2">
                    <p className="flex flex-wrap items-center gap-2 text-sm">
                      <span className="font-medium">{conversation.origin}</span>
                      <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-700">
                        {MODE_LABELS[conversation.control_mode]}
                      </span>
                      <span className="text-slate-500">{formatTime(conversation.last_message_at)}</span>
                    </p>
                    <p className="mt-1 truncate text-sm text-slate-600">{conversation.last_message_preview ?? ""}</p>
                    <p className="text-xs text-slate-500">
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
        <Link to=".." relative="path" className="text-sm text-indigo-700 underline">
          All conversations
        </Link>
      </p>
      <h1 className="text-xl font-bold">Conversation</h1>
      {summary.state.status === "error" && <ErrorState error={summary.state.error} onRetry={summary.reload} />}
      {summary.state.status === "ready" && (
        <p className="text-sm text-slate-600">
          From {summary.state.data.origin} · started {formatTime(summary.state.data.created_at)} ·{" "}
          {MODE_LABELS[summary.state.data.control_mode]}
          {summary.state.data.handoff_requested_at &&
            ` · the customer asked for a person ${formatTime(summary.state.data.handoff_requested_at)}`}
        </p>
      )}
      <Notice tone="info">Replying to customers arrives with the agent workspace. This view is read-only.</Notice>
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
                    message.delivery_status === "SUPPRESSED" ? "border-dashed border-amber-300 bg-amber-50" : "border-slate-200"
                  }`}
                >
                  <p className="flex flex-wrap gap-2 text-xs text-slate-500">
                    <span className="font-semibold text-slate-700">{SENDER_LABELS[message.sender_type]}</span>
                    <span>{formatTime(message.created_at)}</span>
                    {message.delivery_status === "SUPPRESSED" && (
                      <span className="font-medium text-amber-800">Not shown to the customer: a person took over</span>
                    )}
                    {message.outcome && message.outcome !== "ANSWERED" && <span>Outcome: {message.outcome}</span>}
                  </p>
                  <p className="mt-1 whitespace-pre-wrap">{message.body}</p>
                  {message.citations.length > 0 && (
                    <ul className="mt-2 list-disc pl-5 text-xs text-slate-600">
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
    </>
  );
}
