import { useState } from "react";
import { CountTable, DailyBars, formatDuration, formatNumber, StatTile } from "../components/metrics";
import { Card, ErrorState, Loading, Notice, Select } from "../components/ui";
import { api } from "../lib/api";
import { canViewAnalytics } from "../lib/permissions";
import type { Analytics } from "../lib/types";
import { useAsync } from "../lib/useAsync";
import { useWorkspace } from "./WorkspaceLayout";

function percent(part: number, total: number): string {
  return total === 0 ? "–" : `${Math.round((part / total) * 100)}%`;
}

export function OverviewPage() {
  const { workspace } = useWorkspace();
  const [days, setDays] = useState(30);
  const { state, reload } = useAsync(
    () => api<Analytics>(`/workspaces/${workspace.id}/analytics?days=${days}`),
    `${workspace.id}:${days}`,
  );

  if (!canViewAnalytics(workspace.role)) {
    return <Notice tone="error">You do not have permission to view workspace metrics.</Notice>;
  }

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-xl font-bold">Overview</h1>
        <Select id="overview-range" label="Period" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          <option value={7}>Last 7 days</option>
          <option value={30}>Last 30 days</option>
          <option value={90}>Last 90 days</option>
        </Select>
      </div>
      {state.status === "loading" && <Loading label="Loading metrics" />}
      {state.status === "error" && <ErrorState error={state.error} onRetry={reload} />}
      {state.status === "ready" && <OverviewBody data={state.data} />}
    </>
  );
}

function OverviewBody({ data }: { data: Analytics }) {
  const c = data.conversations;
  return (
    <>
      <p className="text-sm text-slate-600">
        {data.range.from} to {data.range.to} (UTC). The ticket queue shows the current state.
      </p>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <StatTile label="Conversations" value={formatNumber(c.total)} />
        <StatTile label="Resolved by AI" value={formatNumber(c.resolved_by_ai)} detail={`${percent(c.resolved_by_ai, c.total)} of conversations`} />
        <StatTile label="Escalated to a person" value={formatNumber(c.escalated)} detail={`${percent(c.escalated, c.total)} of conversations`} />
        <StatTile label="No answer given" value={formatNumber(c.unanswered)} detail="Customer left before an answer" />
      </div>

      <Card>
        <DailyBars title="Conversations per day" rows={data.daily.map((d) => ({ date: d.date, value: d.conversations }))} />
      </Card>

      <div className="grid gap-3 md:grid-cols-4">
        <StatTile label="Open tickets" value={formatNumber(data.queue.open)} />
        <StatTile label="In progress" value={formatNumber(data.queue.in_progress)} />
        <StatTile label="Waiting for customer" value={formatNumber(data.queue.waiting_for_customer)} />
        <StatTile label="Unassigned" value={formatNumber(data.queue.unassigned)} detail="Active tickets nobody owns" />
      </div>

      <div className="grid gap-3 md:grid-cols-4">
        <StatTile label="Median first response" value={formatDuration(data.responses.median_first_response_seconds)} detail="From ticket to first agent reply" />
        <StatTile label="Median time to resolve" value={formatDuration(data.responses.median_resolution_seconds)} />
        <StatTile
          label="Median AI reply time"
          value={data.responses.median_ai_latency_ms === null ? "–" : `${(data.responses.median_ai_latency_ms / 1000).toFixed(1)} s`}
        />
        <StatTile
          label="Customer rating"
          value={data.satisfaction.average === null ? "–" : `${data.satisfaction.average.toFixed(1)} / 5`}
          detail={`${formatNumber(data.satisfaction.count)} ratings`}
        />
      </div>

      <div className="grid gap-3 md:grid-cols-2">
        <Card>
          <CountTable caption="Top intents" rows={data.intents} empty="No AI replies in this period." />
        </Card>
        <Card>
          <CountTable caption="Why conversations were escalated" rows={data.escalation_reasons} empty="No escalations in this period." />
        </Card>
        <Card>
          <CountTable caption="AI reply outcomes" rows={data.ai_outcomes} empty="No AI replies in this period." />
        </Card>
        <Card>
          <CountTable caption="Ticket outcomes" rows={data.resolution_outcomes} empty="No tickets resolved in this period." />
        </Card>
      </div>
    </>
  );
}
