import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { formatNumber, humanize } from "../components/metrics";
import { Button, Card, Empty, ErrorState, Field, FormError, Loading, Notice, Select } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import type { Page, PlatformAuditEvent, PlatformTenant } from "../lib/types";
import { toApiError, useAsync } from "../lib/useAsync";

const PLANS = ["FREE", "STARTER", "GROWTH"];

/**
 * Platform administration: workspaces, their plan and usage counts, suspension, and the platform
 * audit log. Shows no conversation content. Access is enforced by the server.
 */
export function PlatformPage() {
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [version, setVersion] = useState(0);
  const tenants = useAsync(
    () => api<Page<PlatformTenant>>(`/platform/tenants?size=100${search ? `&query=${encodeURIComponent(search)}` : ""}`),
    `${search}:${version}`,
  );
  const log = useAsync(() => api<Page<PlatformAuditEvent>>("/platform/audit-events?size=20"), `log:${version}`);

  if (tenants.state.status === "error" && tenants.state.error.isNotFound) {
    return (
      <main className="mx-auto max-w-3xl space-y-4 px-4 py-10">
        <Notice tone="error">This page does not exist.</Notice>
        <Link to="/" className="text-sm font-medium text-accent-text underline">
          Back to your workspaces
        </Link>
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-5xl space-y-6 px-4 py-10">
      <header className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold tracking-tight text-fg">Platform admin</h1>
        <Link to="/" className="text-sm font-medium text-accent-text underline">
          Your workspaces
        </Link>
      </header>
      <Notice tone="info">
        Every change here is recorded in the platform audit log and in the workspace's own audit log.
      </Notice>

      <Card title="Workspaces">
        <form
          className="mb-4 flex items-end gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            setSearch(query.trim());
          }}
        >
          <div className="flex-1">
            <Field id="platform-search" label="Search by name or address" value={query} onChange={(e) => setQuery(e.target.value)} />
          </div>
          <Button type="submit" variant="secondary">
            Search
          </Button>
        </form>
        {tenants.state.status === "loading" && <Loading label="Loading workspaces" />}
        {tenants.state.status === "error" && <ErrorState error={tenants.state.error} onRetry={tenants.reload} />}
        {tenants.state.status === "ready" && tenants.state.data.items.length === 0 && <Empty>No workspaces match.</Empty>}
        {tenants.state.status === "ready" && tenants.state.data.items.length > 0 && (
          <ul className="divide-y divide-line">
            {tenants.state.data.items.map((tenant) => (
              <TenantItem key={tenant.id} tenant={tenant} onChanged={() => setVersion((v) => v + 1)} />
            ))}
          </ul>
        )}
      </Card>

      <Card title="Recent platform actions">
        {log.state.status === "loading" && <Loading label="Loading the platform audit log" />}
        {log.state.status === "error" && <ErrorState error={log.state.error} onRetry={log.reload} />}
        {log.state.status === "ready" && log.state.data.items.length === 0 && <Empty>No platform actions yet.</Empty>}
        {log.state.status === "ready" && log.state.data.items.length > 0 && (
          <table className="w-full text-left text-sm">
            <tbody>
              {log.state.data.items.map((event) => (
                <tr key={event.id} className="border-t border-line">
                  <td className="py-1.5 pr-3 whitespace-nowrap tabular-nums">{new Date(event.created_at).toLocaleString()}</td>
                  <td className="py-1.5 pr-3">{event.actor_name}</td>
                  <td className="py-1.5 pr-3">{humanize(event.action.replace(/^PLATFORM_/, ""))}</td>
                  <td className="py-1.5">{event.target_tenant_name ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </main>
  );
}

function TenantItem({ tenant, onChanged }: { tenant: PlatformTenant; onChanged: () => void }) {
  const [reason, setReason] = useState("");
  const [suspending, setSuspending] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      setSuspending(false);
      setReason("");
      onChanged();
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setBusy(false);
    }
  }

  function suspend(event: FormEvent) {
    event.preventDefault();
    void run(() => api(`/platform/tenants/${tenant.id}/suspend`, { method: "POST", body: { reason: reason.trim() } }));
  }

  return (
    <li className="space-y-2 py-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <span className="font-medium">{tenant.name}</span>
          <span className="ml-2 text-sm text-fg-subtle">{tenant.slug}</span>
          {tenant.status === "SUSPENDED" && (
            <span className="ml-2 rounded-full bg-warning-soft px-2 py-0.5 text-xs font-medium text-warning-text">Suspended</span>
          )}
          <p className="text-xs text-fg-subtle">
            This month: {formatNumber(tenant.ai_requests_this_month)} AI requests, {formatNumber(tenant.conversations_this_month)}{" "}
            conversations
            {tenant.suspended_reason ? ` · Reason: ${tenant.suspended_reason}` : ""}
          </p>
        </div>
        <div className="flex items-end gap-2">
          <Select
            id={`plan-${tenant.id}`}
            label={`Plan for ${tenant.name}`}
            hideLabel
            value={tenant.plan_code ?? ""}
            disabled={busy}
            onChange={(e) =>
              void run(() => api(`/platform/tenants/${tenant.id}/plan`, { method: "PUT", body: { plan_code: e.target.value } }))
            }
          >
            {PLANS.map((code) => (
              <option key={code} value={code}>
                {humanize(code)}
              </option>
            ))}
          </Select>
          {tenant.status === "ACTIVE" ? (
            <Button variant="danger" disabled={busy} onClick={() => setSuspending(!suspending)}>
              Suspend
            </Button>
          ) : (
            <Button
              variant="secondary"
              disabled={busy}
              onClick={() => void run(() => api(`/platform/tenants/${tenant.id}/reactivate`, { method: "POST" }))}
            >
              Reactivate
            </Button>
          )}
        </div>
      </div>
      <FormError error={error} />
      {suspending && (
        <form onSubmit={suspend} className="flex items-end gap-2">
          <div className="flex-1">
            <Field
              id={`reason-${tenant.id}`}
              label="Reason, shown to the workspace's members"
              maxLength={500}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </div>
          <Button type="submit" variant="danger" disabled={busy || !reason.trim()}>
            Confirm suspension
          </Button>
        </form>
      )}
    </li>
  );
}
