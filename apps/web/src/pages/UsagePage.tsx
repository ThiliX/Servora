import { DailyBars, formatNumber, humanize, Meter, StatTile } from "../components/metrics";
import { Card, ErrorState, Loading, Notice } from "../components/ui";
import { api } from "../lib/api";
import { canViewUsage } from "../lib/permissions";
import type { UsageReport } from "../lib/types";
import { useAsync } from "../lib/useAsync";
import { useWorkspace } from "./WorkspaceLayout";

function dollars(value: number | null): string {
  return value === null ? "Not configured" : `$${value.toFixed(4)}`;
}

export function UsagePage() {
  const { workspace } = useWorkspace();
  const { state, reload } = useAsync(() => api<UsageReport>(`/workspaces/${workspace.id}/usage`), workspace.id);

  if (!canViewUsage(workspace.role)) {
    return <Notice tone="error">You do not have permission to view usage and plan.</Notice>;
  }
  if (state.status === "loading") {
    return <Loading label="Loading usage" />;
  }
  if (state.status === "error") {
    return <ErrorState error={state.error} onRetry={reload} />;
  }
  const { plan, used } = state.data;
  const periodEnd = new Date(state.data.period_end);
  periodEnd.setUTCDate(periodEnd.getUTCDate() - 1);

  return (
    <>
      <h1 className="text-xl font-bold">Usage &amp; plan</h1>
      <Card title={`${plan.name} plan`}>
        <p className="mb-4 text-sm text-slate-600">
          This month: {state.data.period_start.slice(0, 10)} to {periodEnd.toISOString().slice(0, 10)} (UTC). Limits reset on the first
          day of each month. When a limit is reached, the assistant stops answering and customers are offered a person
          instead.
        </p>
        <div className="grid gap-4 md:grid-cols-2">
          <Meter label="AI requests" used={used.ai_requests} limit={plan.monthly_ai_requests} />
          <Meter label="AI tokens" used={used.tokens} limit={plan.monthly_tokens} />
          <Meter label="New conversations" used={used.conversations} limit={plan.monthly_conversations} />
          <Meter label="Documents held" used={used.documents} limit={plan.max_documents} />
        </div>
      </Card>

      <div className="grid gap-3 md:grid-cols-3">
        <StatTile label="Input tokens" value={formatNumber(used.input_tokens)} />
        <StatTile label="Output tokens" value={formatNumber(used.output_tokens)} />
        <StatTile
          label="Estimated provider cost"
          value={dollars(used.estimated_cost_usd)}
          detail={state.data.cost_configured ? "An estimate from configured prices, not a bill" : "Prices are not configured on this server"}
        />
      </div>

      <Card>
        <DailyBars title="AI requests per day" rows={state.data.daily.map((d) => ({ date: d.date, value: d.ai_requests }))} />
      </Card>

      <Card title="AI requests by source">
        {state.data.by_source.length === 0 ? (
          <p className="text-sm text-slate-500">No AI requests this month.</p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="text-slate-500">
                <th className="py-1 font-medium">Source</th>
                <th className="py-1 text-right font-medium">Requests</th>
                <th className="py-1 text-right font-medium">Tokens</th>
                <th className="py-1 text-right font-medium">Estimated cost</th>
              </tr>
            </thead>
            <tbody>
              {state.data.by_source.map((row) => (
                <tr key={row.source} className="border-t border-slate-100">
                  <td className="py-1.5">{humanize(row.source)}</td>
                  <td className="py-1.5 text-right tabular-nums">{formatNumber(row.requests)}</td>
                  <td className="py-1.5 text-right tabular-nums">{formatNumber(row.tokens)}</td>
                  <td className="py-1.5 text-right tabular-nums">{dollars(row.estimated_cost_usd)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="Plans">
        <p className="mb-3 text-sm text-slate-600">
          There is no online payment yet. To change plan, contact Servora; an operator changes it for you.
        </p>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="text-slate-500">
                <th className="py-1 font-medium">Plan</th>
                <th className="py-1 text-right font-medium">AI requests / month</th>
                <th className="py-1 text-right font-medium">Tokens / month</th>
                <th className="py-1 text-right font-medium">Conversations / month</th>
                <th className="py-1 text-right font-medium">Documents</th>
              </tr>
            </thead>
            <tbody>
              {state.data.plans.map((p) => (
                <tr key={p.code} className={`border-t border-slate-100 ${p.code === plan.code ? "font-semibold" : ""}`}>
                  <td className="py-1.5">
                    {p.name}
                    {p.code === plan.code && <span className="ml-2 text-xs font-medium text-indigo-700">Current</span>}
                  </td>
                  <td className="py-1.5 text-right tabular-nums">{formatNumber(p.monthly_ai_requests)}</td>
                  <td className="py-1.5 text-right tabular-nums">{formatNumber(p.monthly_tokens)}</td>
                  <td className="py-1.5 text-right tabular-nums">{formatNumber(p.monthly_conversations)}</td>
                  <td className="py-1.5 text-right tabular-nums">{formatNumber(p.max_documents)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  );
}
