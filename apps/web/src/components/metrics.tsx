import { useState } from "react";

const numberFormat = new Intl.NumberFormat();

export function formatNumber(value: number): string {
  return numberFormat.format(value);
}

/** A duration in seconds as "45 s", "12 min" or "3.5 h"; a dash when there is no data. */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) {
    return "–";
  }
  if (seconds < 60) {
    return `${Math.round(seconds)} s`;
  }
  if (seconds < 3600) {
    return `${Math.round(seconds / 60)} min`;
  }
  if (seconds < 86400) {
    return `${(seconds / 3600).toFixed(1)} h`;
  }
  return `${(seconds / 86400).toFixed(1)} d`;
}

/** Turns an enum value such as INSUFFICIENT_EVIDENCE into "Insufficient evidence". */
export function humanize(value: string): string {
  const words = value.toLowerCase().replaceAll("_", " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function StatTile({ label, value, detail }: { label: string; value: string; detail?: string }) {
  return (
    <div className="rounded-xl border border-line bg-surface p-5 shadow-card">
      <p className="text-sm text-fg-muted">{label}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums text-fg">{value}</p>
      {detail && <p className="mt-1 text-xs text-fg-subtle">{detail}</p>}
    </div>
  );
}

/** A labelled meter of used against a limit. The text carries the numbers; the bar only repeats them. */
export function Meter({ label, used, limit, unit = "" }: { label: string; used: number; limit: number; unit?: string }) {
  const ratio = limit > 0 ? Math.min(used / limit, 1) : 1;
  const full = used >= limit;
  const near = !full && ratio >= 0.8;
  return (
    <div>
      <div className="flex items-baseline justify-between text-sm">
        <span className="font-medium text-fg">{label}</span>
        <span className="tabular-nums text-fg">
          {formatNumber(used)} / {formatNumber(limit)}
          {unit}
        </span>
      </div>
      <div
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={limit}
        aria-valuenow={Math.min(used, limit)}
        className="mt-1 h-2 overflow-hidden rounded-full bg-subtle-strong"
      >
        <div
          className={`h-full rounded-full ${full ? "bg-danger" : near ? "bg-warning" : "bg-accent"}`}
          style={{ width: `${ratio * 100}%` }}
        />
      </div>
      {full && <p className="mt-1 text-xs font-medium text-danger-text">Limit reached for this month.</p>}
      {near && <p className="mt-1 text-xs text-warning-text">Over 80% used.</p>}
    </div>
  );
}

/**
 * One series of daily values as thin bars on a shared baseline, with a hover label per bar and a
 * table view of the same numbers for screen readers and exact reading.
 */
export function DailyBars({ title, rows }: { title: string; rows: { date: string; value: number }[] }) {
  const [hovered, setHovered] = useState<number | null>(null);
  const peak = Math.max(0, ...rows.map((row) => row.value));
  // Scales the bars; at least 1 so an all-zero series does not divide by zero.
  const max = Math.max(1, peak);
  const active = hovered === null ? null : rows[hovered];
  return (
    <figure>
      <figcaption className="flex items-baseline justify-between text-sm">
        <span className="font-medium text-fg">{title}</span>
        <span className="tabular-nums text-fg-muted" aria-live="polite">
          {active ? `${active.date}: ${formatNumber(active.value)}` : peak === 0 ? "None in this period" : `Peak ${formatNumber(peak)} per day`}
        </span>
      </figcaption>
      <div className="mt-3 flex h-32 items-end gap-0.5 border-b border-line-strong" aria-hidden="true">
        {rows.map((row, index) => (
          <div
            key={row.date}
            className="flex h-full flex-1 items-end"
            onMouseEnter={() => setHovered(index)}
            onMouseLeave={() => setHovered(null)}
            title={`${row.date}: ${row.value}`}
          >
            <div
              className={`w-full rounded-t ${hovered === index ? "bg-accent-hover" : "bg-accent"}`}
              style={{ height: row.value === 0 ? "0" : `max(2px, ${(row.value / max) * 100}%)` }}
            />
          </div>
        ))}
      </div>
      <div className="mt-1 flex justify-between text-xs text-fg-subtle" aria-hidden="true">
        <span>{rows[0]?.date}</span>
        <span>{rows[rows.length - 1]?.date}</span>
      </div>
      <details className="mt-2 text-sm">
        <summary className="cursor-pointer text-fg-muted">Show as table</summary>
        <table className="mt-2 w-full text-left">
          <thead>
            <tr className="text-fg-subtle">
              <th className="py-1 font-medium">Date</th>
              <th className="py-1 text-right font-medium">{title}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.date} className="border-t border-line">
                <td className="py-1">{row.date}</td>
                <td className="py-1 text-right tabular-nums">{formatNumber(row.value)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </figure>
  );
}

/** A ranked list of counts, as a table: exact numbers are what this data is read for. */
export function CountTable({ caption, rows, empty }: { caption: string; rows: { key: string; count: number }[]; empty: string }) {
  if (rows.length === 0) {
    return (
      <div>
        <h3 className="text-sm font-medium text-fg">{caption}</h3>
        <p className="mt-2 text-sm text-fg-subtle">{empty}</p>
      </div>
    );
  }
  return (
    <table className="w-full text-left text-sm">
      <caption className="mb-2 text-left font-medium text-fg">{caption}</caption>
      <tbody>
        {rows.map((row) => (
          <tr key={row.key} className="border-t border-line">
            <td className="py-1.5">{humanize(row.key)}</td>
            <td className="py-1.5 text-right tabular-nums">{formatNumber(row.count)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
