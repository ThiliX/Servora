import { useEffect, useState } from "react";
import { Link } from "react-router";
import { api } from "../lib/api";
import type { NotificationList } from "../lib/types";
import { useAsync } from "../lib/useAsync";
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
      <Button variant="secondary" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        Notifications{unread > 0 ? ` (${unread})` : ""}
      </Button>
      {open && state.status === "ready" && (
        <div className="absolute right-0 z-10 mt-1 w-72 rounded-md border border-slate-200 bg-white p-2 shadow-lg">
          {state.data.items.length === 0 ? (
            <p className="p-2 text-sm text-slate-500">Nothing new.</p>
          ) : (
            <ul className="max-h-80 space-y-1 overflow-y-auto">
              {state.data.items.map((item) => (
                <li key={item.id}>
                  <Link
                    to={item.ticket_id ? `/w/${workspaceId}/inbox/${item.ticket_id}` : `/w/${workspaceId}/inbox`}
                    onClick={() => setOpen(false)}
                    className={`block rounded p-2 text-sm hover:bg-slate-50 ${item.read ? "text-slate-500" : "font-medium"}`}
                  >
                    Ticket #{item.ticket_number}: {LABELS[item.type]}
                  </Link>
                </li>
              ))}
            </ul>
          )}
          {unread > 0 && (
            <Button variant="secondary" className="mt-2 w-full" onClick={() => void markAllRead()}>
              Mark all as read
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
