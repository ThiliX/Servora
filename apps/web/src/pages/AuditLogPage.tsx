import { useState } from "react";
import { humanize } from "../components/metrics";
import { Button, Card, Empty, ErrorState, Loading, Notice, PageHeader } from "../components/ui";
import { api } from "../lib/api";
import { canViewAuditLog } from "../lib/permissions";
import type { AuditEvent, Page } from "../lib/types";
import { useAsync } from "../lib/useAsync";
import { useWorkspace } from "./WorkspaceLayout";

const PAGE_SIZE = 50;

function describe(metadata: Record<string, string>): string {
  return Object.entries(metadata)
    .map(([key, value]) => `${key.replaceAll("_", " ")}: ${value}`)
    .join(", ");
}

export function AuditLogPage() {
  const { workspace } = useWorkspace();
  const [page, setPage] = useState(0);
  const { state, reload } = useAsync(
    () => api<Page<AuditEvent>>(`/workspaces/${workspace.id}/audit-events?page=${page}&size=${PAGE_SIZE}`),
    `${workspace.id}:${page}`,
  );

  if (!canViewAuditLog(workspace.role)) {
    return <Notice tone="error">You do not have permission to view the audit log.</Notice>;
  }

  return (
    <>
      <PageHeader title="Audit log" description="Every change made in this workspace: who made it, and when." />
      <p className="text-sm text-fg-muted">
        Changes to the team, configuration, knowledge, tickets and data, newest first. Actions by Servora operators are
        listed too.
      </p>
      <Card>
        {state.status === "loading" && <Loading label="Loading the audit log" />}
        {state.status === "error" && <ErrorState error={state.error} onRetry={reload} />}
        {state.status === "ready" && state.data.items.length === 0 && <Empty>Nothing has been recorded yet.</Empty>}
        {state.status === "ready" && state.data.items.length > 0 && (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="text-fg-subtle">
                    <th className="py-1 pr-3 font-medium">When</th>
                    <th className="py-1 pr-3 font-medium">Who</th>
                    <th className="py-1 pr-3 font-medium">Action</th>
                    <th className="py-1 font-medium">Details</th>
                  </tr>
                </thead>
                <tbody>
                  {state.data.items.map((event) => (
                    <tr key={event.id} className="border-t border-line align-top">
                      <td className="py-1.5 pr-3 whitespace-nowrap tabular-nums">{new Date(event.created_at).toLocaleString()}</td>
                      <td className="py-1.5 pr-3">{event.actor_name ?? "System"}</td>
                      <td className="py-1.5 pr-3">{humanize(event.action)}</td>
                      <td className="py-1.5 text-fg-muted">{describe(event.metadata)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-3 flex items-center justify-between text-sm">
              <span className="text-fg-muted">
                Page {page + 1} of {Math.max(1, Math.ceil(state.data.total / PAGE_SIZE))}
              </span>
              <div className="flex gap-2">
                <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
                  Newer
                </Button>
                <Button
                  variant="secondary"
                  disabled={(page + 1) * PAGE_SIZE >= state.data.total}
                  onClick={() => setPage(page + 1)}
                >
                  Older
                </Button>
              </div>
            </div>
          </>
        )}
      </Card>
    </>
  );
}
