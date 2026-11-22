import { useEffect, useState } from "react";
import { Link } from "react-router";
import { api } from "../lib/api";
import type { NotificationList } from "../lib/types";
import { useAsync } from "../lib/useAsync";
import { Bell } from "lucide-react";
import { Button } from "./ui";

const REFRESH_MS = 30_000;

const LABELS: Record<NotificationList["items"][number]["type"], string> = {
  TICKET_ASSIGNED: "assigned to you",
  CUSTOMER_MESSAGE: "new message from the customer",
};

/** Unread count in the header, and a list of recent notifications that link to the inbox. */
export function NotificationsBell({ workspaceId }: { workspaceId: string }) {
  const path = `/workspaces/${workspaceId}/notifications`;
  const { state, reload } = useAsync(() => api<NotificationList>(path), path);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const timer = window.setInterval(reload, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [reload]);

  const unread = state.status === "ready" ? state.data.unread_count : 0;

  async function markAllRead() {
    await api(`${path}/read`, { method: "POST", body: { ids: [] } });
    reload();
  }

  return (
    <div className="relative">
      <button
        type="button"
        aria-expanded={open}
        aria-label={`Notifications${unread > 0 ? ` (${unread})` : ""}`}
        onClick={() => setOpen((o) => !o)}
        className="relative inline-flex size-9 items-center justify-center rounded-lg text-fg-muted transition-colors hover:bg-subtle hover:text-fg"
      >
        <Bell aria-hidden="true" className="size-[18px]" />
        {unread > 0 && (
          <span
            aria-hidden="true"
            className="absolute top-1 right-1 flex min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] leading-4 font-semibold text-white"
          >
            {unread > 9 ? "9+" : unread}
          </span>
        )}
      </button>
      {open && state.status === "ready" && (
        <div className="absolute right-0 z-30 mt-2 w-80 rounded-xl border border-line bg-surface p-2 shadow-raised">
          <p className="px-2 pt-1 pb-2 text-xs font-semibold tracking-wide text-fg-subtle uppercase">Notifications</p>
          {state.data.items.length === 0 ? (
            <p className="px-2 py-6 text-center text-sm text-fg-subtle">You're all caught up.</p>
          ) : (
            <ul className="max-h-80 space-y-1 overflow-y-auto">
              {state.data.items.map((item) => (
                <li key={item.id}>
                  <Link
                    to={item.ticket_id ? `/w/${workspaceId}/inbox/${item.ticket_id}` : `/w/${workspaceId}/inbox`}
                    onClick={() => setOpen(false)}
                    className={`flex items-start gap-2 rounded-lg p-2 text-sm hover:bg-subtle ${item.read ? "text-fg-subtle" : "font-medium text-fg"}`}
                  >
                    <span
                      aria-hidden="true"
                      className={`mt-1.5 size-1.5 shrink-0 rounded-full ${item.read ? "bg-transparent" : "bg-accent"}`}
                    />
                    <span>
                      Ticket #{item.ticket_number}: {LABELS[item.type]}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
          {unread > 0 && (
            <Button variant="ghost" size="sm" className="mt-1 w-full" onClick={() => void markAllRead()}>
              Mark all as read
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
