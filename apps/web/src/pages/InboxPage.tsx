import { useEffect, useState, type FormEvent } from "react";
import { Link, useParams } from "react-router";
import { Button, Empty, ErrorState, FormError, Loading, Notice, Select } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import { canAssignTickets } from "../lib/permissions";
import type {
  Assignee,
  ControlMode,
  InternalNote,
  Page,
  ThreadMessage,
  TicketDetail,
  TicketOutcome,
  TicketPriority,
  TicketStatus,
  TicketSummary,
} from "../lib/types";
import { toApiError, useAsync } from "../lib/useAsync";
import { formatTime } from "./ConversationsPage";
import { useWorkspace } from "./WorkspaceLayout";

const QUEUE_REFRESH_MS = 15_000;
const THREAD_REFRESH_MS = 5_000;

export const STATUS_LABELS: Record<TicketStatus, string> = {
  OPEN: "Open",
  IN_PROGRESS: "In progress",
  WAITING_FOR_CUSTOMER: "Waiting for customer",
  RESOLVED: "Resolved",
  CLOSED: "Closed",
};

const PRIORITY_STYLES: Record<TicketPriority, string> = {
  LOW: "bg-slate-100 text-slate-700",
  NORMAL: "bg-slate-100 text-slate-700",
  HIGH: "bg-amber-100 text-amber-800",
  URGENT: "bg-red-100 text-red-800",
};

const REASON_LABELS: Record<string, string> = {
  CUSTOMER_REQUEST: "Customer asked for a person",
  INSUFFICIENT_EVIDENCE: "Sources disagreed",
  SENSITIVE_REQUEST: "Needs a person",
  REPEATED_MISUNDERSTANDING: "Assistant could not help twice",
  SERVICE_ERROR: "Assistant unavailable",
  RISK_RULE: "Legal, fraud or data concern",
  AGENT_INITIATED: "Taken over by the team",
};

const OUTCOME_LABELS: Record<TicketOutcome, string> = {
  SOLVED: "Solved",
  NOT_SOLVED: "Not solved",
  NO_REPLY_FROM_CUSTOMER: "No reply from customer",
  DUPLICATE: "Duplicate",
};

/** Who is answering the customer right now, in plain words. */
export function controlLabel(mode: ControlMode, assignee: string | null, mine: boolean): string {
  if (mode === "AI_ACTIVE") {
    return "The assistant is replying";
  }
  if (mode === "ESCALATION_PENDING") {
    return "Waiting for a person to take over";
  }
  return mine ? "You are replying" : `${assignee ?? "A team member"} is replying`;
}

/** Re-runs {@code reload} on an interval while the component is mounted. */
function useInterval(reload: () => void, ms: number) {
  useEffect(() => {
    const timer = window.setInterval(reload, ms);
    return () => window.clearInterval(timer);
  }, [reload, ms]);
}

type QueueView = "MINE" | "UNASSIGNED" | "ALL";

function Queue({ selected, reloadKey }: { selected: string | undefined; reloadKey: number }) {
  const { workspace } = useWorkspace();
  const [view, setView] = useState<QueueView>("ALL");
  const [state, setState] = useState<"ACTIVE" | "DONE">("ACTIVE");
  const path = `/workspaces/${workspace.id}/tickets?view=${view}&state=${state}&size=50`;
  const queue = useAsync(() => api<Page<TicketSummary>>(path), path);
  useInterval(queue.reload, QUEUE_REFRESH_MS);
  // Refresh at once after an action on the open ticket, so the list never shows a stale assignee or status.
  const { reload: reloadQueue } = queue;
  useEffect(() => {
    if (reloadKey > 0) {
      reloadQueue();
    }
  }, [reloadKey, reloadQueue]);

  return (
    <section aria-label="Ticket queue" className="rounded-lg border border-slate-200 bg-white p-3">
      <div className="mb-2 flex flex-wrap gap-1" role="group" aria-label="Which tickets">
        {(["ALL", "MINE", "UNASSIGNED"] as const).map((v) => (
          <Button key={v} variant={view === v ? "primary" : "secondary"} aria-pressed={view === v} onClick={() => setView(v)}>
            {v === "ALL" ? "All" : v === "MINE" ? "Mine" : "Unassigned"}
          </Button>
        ))}
      </div>
      <Select id="queue-state" label="Show" value={state} onChange={(e) => setState(e.target.value as "ACTIVE" | "DONE")}>
        <option value="ACTIVE">Needs work</option>
        <option value="DONE">Resolved</option>
      </Select>
      <div className="mt-3">
        {queue.state.status === "loading" && <Loading label="Loading tickets" />}
        {queue.state.status === "error" && <ErrorState error={queue.state.error} onRetry={queue.reload} />}
        {queue.state.status === "ready" && queue.state.data.items.length === 0 && <Empty>No tickets here.</Empty>}
        {queue.state.status === "ready" && queue.state.data.items.length > 0 && (
          <ul className="space-y-1">
            {queue.state.data.items.map((ticket) => (
              <li key={ticket.id}>
                <Link
                  to={`/w/${workspace.id}/inbox/${ticket.id}`}
                  aria-current={ticket.id === selected ? "page" : undefined}
                  className={`block rounded-md p-2 text-sm hover:bg-slate-50 ${ticket.id === selected ? "bg-indigo-50" : ""}`}
                >
                  <span className="flex flex-wrap items-center gap-1">
                    <span className="font-semibold">#{ticket.number}</span>
                    <span className={`rounded-full px-1.5 text-xs ${PRIORITY_STYLES[ticket.priority]}`}>{ticket.priority.toLowerCase()}</span>
                    <span className="text-xs text-slate-500">{STATUS_LABELS[ticket.status]}</span>
                  </span>
                  <span className="block truncate text-slate-600">{ticket.last_message_preview ?? ""}</span>
                  <span className="block text-xs text-slate-500">{ticket.assignee_name ?? "Unassigned"}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

type ThreadItem =
  | { kind: "message"; at: string; message: ThreadMessage }
  | { kind: "note"; at: string; note: InternalNote };

function Thread({
  detail,
  onChanged,
  reloadKey,
}: {
  detail: TicketDetail;
  onChanged: () => void;
  reloadKey: number;
}) {
  const { workspace } = useWorkspace();
  const conversation = `/workspaces/${workspace.id}/conversations/${detail.ticket.conversation_id}`;
  const messagesPath = `${conversation}/messages?size=100`;
  const messages = useAsync(() => api<Page<ThreadMessage>>(messagesPath), messagesPath);
  const notes = useAsync(() => api<InternalNote[]>(`${conversation}/notes`), `${conversation}/notes`);
  const [mode, setMode] = useState<"reply" | "note">("reply");
  const [text, setText] = useState("");
  const [suggestionId, setSuggestionId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const { reload: reloadMessages } = messages;
  const { reload: reloadNotes } = notes;

  useInterval(reloadMessages, THREAD_REFRESH_MS);
  useEffect(() => {
    reloadMessages();
    reloadNotes();
  }, [reloadKey, reloadMessages, reloadNotes]);

  const canReply =
    detail.assigned_to_me && detail.ticket.control_mode === "HUMAN_ACTIVE" && detail.ticket.conversation_status === "OPEN";

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!text.trim()) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (mode === "reply") {
        await api(`${conversation}/replies`, { method: "POST", body: { body: text.trim(), suggestion_id: suggestionId } });
      } else {
        await api(`${conversation}/notes`, { method: "POST", body: { body: text.trim() } });
      }
      setText("");
      setSuggestionId(null);
      reloadMessages();
      reloadNotes();
      onChanged();
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setBusy(false);
    }
  }

  async function suggest() {
    setBusy(true);
    setError(null);
    try {
      const suggestion = await api<{ id: string; body: string; usable: boolean }>(`${conversation}/suggestions`, { method: "POST" });
      reloadMessages();
      if (suggestion.usable) {
        setMode("reply");
        setText(suggestion.body);
        setSuggestionId(suggestion.id);
      } else {
        setError(toApiError(new Error("no usable suggestion")));
      }
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setBusy(false);
    }
  }

  const items: ThreadItem[] = [
    ...(messages.state.status === "ready"
      ? messages.state.data.items.map((message) => ({ kind: "message" as const, at: message.created_at, message }))
      : []),
    ...(notes.state.status === "ready" ? notes.state.data.map((note) => ({ kind: "note" as const, at: note.created_at, note })) : []),
  ].sort((a, b) => a.at.localeCompare(b.at));

  return (
    <section aria-label="Conversation" className="flex min-h-[28rem] flex-col rounded-lg border border-slate-200 bg-white p-3">
      {messages.state.status === "loading" && <Loading label="Loading conversation" />}
      {messages.state.status === "error" && <ErrorState error={messages.state.error} onRetry={reloadMessages} />}
      <ol className="flex-1 space-y-2 overflow-y-auto" aria-label="Messages and notes">
        {items.map((item) =>
          item.kind === "note" ? (
            <li key={`n-${item.note.id}`} className="rounded-md border border-yellow-300 bg-yellow-50 p-2 text-sm">
              <p className="text-xs font-semibold text-yellow-900">
                Internal note · {item.note.author_name} · {formatTime(item.note.created_at)}
              </p>
              <p className="whitespace-pre-wrap">{item.note.body}</p>
            </li>
          ) : (
            <MessageItem
              key={item.message.id}
              message={item.message}
              onUse={
                item.message.visibility === "AI_SUGGESTION" && canReply
                  ? () => {
                      setMode("reply");
                      setText(item.message.body);
                      setSuggestionId(item.message.id);
                    }
                  : undefined
              }
            />
          ),
        )}
      </ol>
      <form onSubmit={submit} className="mt-3 space-y-2 border-t border-slate-200 pt-3" noValidate>
        <FormError error={error} />
        <div role="tablist" aria-label="Write" className="flex gap-1">
          <Button role="tab" aria-selected={mode === "reply"} variant={mode === "reply" ? "primary" : "secondary"} onClick={() => setMode("reply")}>
            Reply to customer
          </Button>
          <Button role="tab" aria-selected={mode === "note"} variant={mode === "note" ? "primary" : "secondary"} onClick={() => setMode("note")}>
            Internal note
          </Button>
          <Button variant="secondary" disabled={busy || detail.ticket.conversation_status !== "OPEN"} onClick={() => void suggest()}>
            Draft with AI
          </Button>
        </div>
        {mode === "reply" && !canReply && (
          <p className="text-xs text-slate-600">
            {detail.ticket.conversation_status !== "OPEN" ? "This conversation has ended." : "Take over the conversation to reply."}
          </p>
        )}
        {suggestionId && mode === "reply" && <p className="text-xs text-indigo-700">Using an AI draft. Check and edit it before sending.</p>}
        <label htmlFor="inbox-composer" className="sr-only">
          {mode === "reply" ? "Reply to the customer" : "Internal note, not shown to the customer"}
        </label>
        <textarea
          id="inbox-composer"
          rows={3}
          maxLength={4000}
          value={text}
          onChange={(e) => setText(e.target.value)}
          disabled={mode === "reply" && !canReply}
          placeholder={mode === "reply" ? "Write to the customer…" : "Only your team can see notes"}
          className={`block w-full rounded-md border px-3 py-2 text-sm disabled:bg-slate-100 ${
            mode === "note" ? "border-yellow-300 bg-yellow-50" : "border-slate-300"
          }`}
        />
        <Button type="submit" disabled={busy || !text.trim() || (mode === "reply" && !canReply)}>
          {mode === "reply" ? "Send reply" : "Add note"}
        </Button>
      </form>
    </section>
  );
}

function MessageItem({ message, onUse }: { message: ThreadMessage; onUse?: () => void }) {
  const suggestion = message.visibility === "AI_SUGGESTION";
  const suppressed = message.delivery_status === "SUPPRESSED";
  const who = { CUSTOMER: "Customer", ASSISTANT: "Assistant", AGENT: "Team", SYSTEM: "Notice" }[message.sender_type];
  return (
    <li
      className={`rounded-md border p-2 text-sm ${
        suggestion ? "border-dashed border-indigo-300 bg-indigo-50" : suppressed ? "border-dashed border-amber-300 bg-amber-50" : "border-slate-200"
      } ${message.sender_type === "CUSTOMER" ? "mr-8" : "ml-8"}`}
    >
      <p className="flex flex-wrap gap-2 text-xs text-slate-500">
        <span className="font-semibold text-slate-700">{suggestion ? "AI draft (not sent)" : who}</span>
        <span>{formatTime(message.created_at)}</span>
        {suppressed && <span className="text-amber-800">Not shown to the customer</span>}
      </p>
      <p className="whitespace-pre-wrap">{message.body}</p>
      {message.citations.length > 0 && (
        <p className="mt-1 text-xs text-slate-500">
          {/* Two passages from one document are one source here. */}
          Sources: {[...new Set(message.citations.map((c) => c.filename))].join(", ")}
        </p>
      )}
      {onUse && (
        <Button variant="secondary" className="mt-2" onClick={onUse}>
          Use this draft
        </Button>
      )}
    </li>
  );
}

function Context({ detail, onChanged }: { detail: TicketDetail; onChanged: (detail?: TicketDetail) => void }) {
  const { workspace } = useWorkspace();
  const ticket = detail.ticket;
  const base = `/workspaces/${workspace.id}/tickets/${ticket.id}`;
  const manager = canAssignTickets(workspace.role);
  const assignees = useAsync(
    () => (manager ? api<Assignee[]>(`/workspaces/${workspace.id}/tickets/assignees`) : Promise.resolve([])),
    `assignees-${workspace.id}-${manager}`,
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [outcome, setOutcome] = useState<TicketOutcome>("SOLVED");
  const [note, setNote] = useState("");

  async function run(path: string, init: Parameters<typeof api>[1]) {
    setBusy(true);
    setError(null);
    try {
      onChanged(await api<TicketDetail>(path, init));
    } catch (caught) {
      setError(toApiError(caught));
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  const active = ticket.status !== "RESOLVED" && ticket.status !== "CLOSED";
  const canTakeOver =
    active && ticket.conversation_status === "OPEN" && !(ticket.control_mode === "HUMAN_ACTIVE" && detail.assigned_to_me);

  return (
    <section aria-label="Ticket" className="space-y-3 rounded-lg border border-slate-200 bg-white p-3 text-sm">
      <div>
        <h2 className="text-base font-semibold">Ticket #{ticket.number}</h2>
        <p className="text-slate-600">{REASON_LABELS[ticket.reason] ?? ticket.reason}</p>
        <p className="text-xs text-slate-500">
          From {ticket.origin} · opened {formatTime(ticket.created_at)}
        </p>
      </div>
      <p role="status" className="rounded-md bg-slate-100 px-2 py-1 font-medium">
        {ticket.conversation_status === "CLOSED"
          ? "This conversation has ended"
          : controlLabel(ticket.control_mode, ticket.assignee_name, detail.assigned_to_me)}
      </p>
      <FormError error={error} />
      {canTakeOver && (
        <Button
          disabled={busy}
          onClick={() => void run(`/workspaces/${workspace.id}/conversations/${ticket.conversation_id}/takeover`, {
            method: "POST",
            body: { expected_version: ticket.conversation_version },
          })}
        >
          {busy ? "Taking over…" : "Take over"}
        </Button>
      )}
      <dl className="grid grid-cols-2 gap-1">
        <dt className="text-slate-500">Status</dt>
        <dd>{STATUS_LABELS[ticket.status]}</dd>
        <dt className="text-slate-500">Assigned to</dt>
        <dd>{ticket.assignee_name ?? "Nobody"}</dd>
      </dl>
      {manager && active && assignees.state.status === "ready" && (
        <Select
          id="ticket-assignee"
          label="Assign to"
          value={ticket.assignee_membership_id ?? ""}
          disabled={busy}
          onChange={(e) => void run(`${base}/assignee`, { method: "PUT", body: { membership_id: e.target.value || null } })}
        >
          <option value="">Nobody</option>
          {assignees.state.data.map((a) => (
            <option key={a.membership_id} value={a.membership_id}>
              {a.display_name}
            </option>
          ))}
        </Select>
      )}
      {detail.can_work && active && (
        <Select
          id="ticket-priority"
          label="Priority"
          value={ticket.priority}
          disabled={busy}
          onChange={(e) => void run(base, { method: "PATCH", body: { priority: e.target.value } })}
        >
          {(["LOW", "NORMAL", "HIGH", "URGENT"] as const).map((p) => (
            <option key={p} value={p}>
              {p.charAt(0) + p.slice(1).toLowerCase()}
            </option>
          ))}
        </Select>
      )}
      {detail.can_work && active && (
        <form
          className="space-y-2 border-t border-slate-200 pt-3"
          onSubmit={(e) => {
            e.preventDefault();
            void run(`${base}/resolve`, { method: "POST", body: { outcome, note } });
          }}
        >
          <Select id="ticket-outcome" label="Outcome" value={outcome} onChange={(e) => setOutcome(e.target.value as TicketOutcome)}>
            {(Object.keys(OUTCOME_LABELS) as TicketOutcome[]).map((o) => (
              <option key={o} value={o}>
                {OUTCOME_LABELS[o]}
              </option>
            ))}
          </Select>
          <label htmlFor="ticket-resolution-note" className="block text-sm font-medium text-slate-700">
            Resolution note (team only)
          </label>
          <textarea
            id="ticket-resolution-note"
            rows={2}
            maxLength={2000}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            className="block w-full rounded-md border border-slate-300 px-2 py-1"
          />
          <Button type="submit" disabled={busy}>
            Resolve
          </Button>
        </form>
      )}
      {detail.can_work && ticket.status === "RESOLVED" && (
        <Button variant="secondary" disabled={busy} onClick={() => void run(`${base}/close`, { method: "POST" })}>
          Close ticket
        </Button>
      )}
      {ticket.resolution_outcome && <p>Outcome: {OUTCOME_LABELS[ticket.resolution_outcome]}</p>}
      {detail.feedback && (
        <p>
          Customer rating: {detail.feedback.rating} of 5{detail.feedback.comment ? ` — “${detail.feedback.comment}”` : ""}
        </p>
      )}
      <details>
        <summary className="cursor-pointer text-slate-600">History</summary>
        <ol className="mt-2 space-y-1 text-xs text-slate-600">
          {detail.events.map((event, index) => (
            <li key={index}>
              {formatTime(event.created_at)} · {event.event_type.toLowerCase().replace(/_/g, " ")}
              {event.to_value && event.event_type !== "ASSIGNED" ? ` → ${event.to_value.toLowerCase().replace(/_/g, " ")}` : ""}
              {event.actor_name ? ` · ${event.actor_name}` : ""}
            </li>
          ))}
        </ol>
      </details>
    </section>
  );
}

export function InboxPage() {
  const { workspace } = useWorkspace();
  const { ticketId } = useParams();
  const path = ticketId ? `/workspaces/${workspace.id}/tickets/${ticketId}` : "";
  const detail = useAsync(() => (ticketId ? api<TicketDetail>(path) : Promise.resolve(null)), path || "none");
  const [current, setCurrent] = useState<TicketDetail | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  useInterval(detail.reload, THREAD_REFRESH_MS);

  useEffect(() => {
    setCurrent(detail.state.status === "ready" ? detail.state.data : null);
  }, [detail.state]);

  function changed(updated?: TicketDetail) {
    if (updated) {
      setCurrent(updated);
    }
    detail.reload();
    setReloadKey((k) => k + 1);
  }

  return (
    <>
      <h1 className="text-xl font-bold">Inbox</h1>
      <div className="grid gap-3 lg:grid-cols-[15rem_minmax(0,1fr)_16rem]">
        <Queue selected={ticketId} reloadKey={reloadKey} />
        {!ticketId && <Notice tone="info">Choose a ticket to see the conversation.</Notice>}
        {ticketId && detail.state.status === "loading" && !current && <Loading label="Loading ticket" />}
        {ticketId && detail.state.status === "error" && <ErrorState error={detail.state.error} onRetry={detail.reload} />}
        {current && (
          <>
            <Thread detail={current} onChanged={() => changed()} reloadKey={reloadKey} />
            <Context detail={current} onChanged={changed} />
          </>
        )}
      </div>
    </>
  );
}
