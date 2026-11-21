import { useState, type FormEvent } from "react";
import { Button, Card, ErrorState, Field, FormError, Loading, Notice, Select } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import { canConfigureAssistant } from "../lib/permissions";
import type { Widget } from "../lib/types";
import { toApiError, useAsync } from "../lib/useAsync";
import { useWorkspace } from "./WorkspaceLayout";

/** One origin per line; blank lines are ignored. Validation and canonical form are the server's job. */
export function parseOrigins(text: string): string[] {
  return text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

export function embedSnippet(loaderUrl: string, publicKey: string): string {
  return `<script type="module" src="${loaderUrl}" data-widget-key="${publicKey}" async></script>`;
}

function AppearanceForm({ widget, onSaved }: { widget: Widget; onSaved: () => void }) {
  const { workspace } = useWorkspace();
  const [title, setTitle] = useState(widget.title);
  const [color, setColor] = useState(widget.theme_color);
  const [position, setPosition] = useState(widget.position);
  const [branding, setBranding] = useState(widget.show_branding);
  const [enabled, setEnabled] = useState(widget.status === "ACTIVE");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [saved, setSaved] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setSaved(false);
    try {
      await api<Widget>(`/workspaces/${workspace.id}/widget`, {
        method: "PATCH",
        body: {
          title,
          theme_color: color,
          position,
          show_branding: branding,
          status: enabled ? "ACTIVE" : "DISABLED",
        },
      });
      setSaved(true);
      onSaved();
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={submit} className="max-w-xl space-y-4" noValidate>
      <FormError error={error} />
      {saved && <Notice tone="success">Widget settings saved.</Notice>}
      <Field id="widget-title" label="Title shown to customers" required maxLength={80} value={title} onChange={(e) => setTitle(e.target.value)} />
      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label htmlFor="widget-color" className="block text-sm font-medium text-slate-700">
            Theme colour
          </label>
          <input
            id="widget-color"
            type="color"
            value={color}
            onChange={(e) => setColor(e.target.value)}
            className="mt-1 h-10 w-20 rounded-md border border-slate-300 bg-white"
          />
        </div>
        <Select id="widget-position" label="Position" value={position} onChange={(e) => setPosition(e.target.value as Widget["position"])}>
          <option value="BOTTOM_RIGHT">Bottom right</option>
          <option value="BOTTOM_LEFT">Bottom left</option>
        </Select>
      </div>
      <label className="flex items-center gap-2 text-sm text-slate-700">
        <input type="checkbox" checked={branding} onChange={(e) => setBranding(e.target.checked)} className="size-4" />
        Show "Powered by Servora"
      </label>
      <label className="flex items-center gap-2 text-sm text-slate-700">
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} className="size-4" />
        Widget enabled
      </label>
      <Button type="submit" disabled={submitting || !title.trim()}>
        {submitting ? "Saving…" : "Save widget"}
      </Button>
    </form>
  );
}

function OriginsForm({ widget, onSaved }: { widget: Widget; onSaved: () => void }) {
  const { workspace } = useWorkspace();
  const [text, setText] = useState(widget.allowed_origins.join("\n"));
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [saved, setSaved] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setSaved(false);
    try {
      const updated = await api<Widget>(`/workspaces/${workspace.id}/widget/allowed-origins`, {
        method: "PUT",
        body: { origins: parseOrigins(text) },
      });
      // Show the list as the server stored it (lower-cased, default ports removed, duplicates merged).
      setText(updated.allowed_origins.join("\n"));
      setSaved(true);
      onSaved();
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={submit} className="max-w-xl space-y-3" noValidate>
      <FormError error={error} />
      {saved && <Notice tone="success">Allowed websites saved.</Notice>}
      <label htmlFor="widget-origins" className="block text-sm font-medium text-slate-700">
        Websites allowed to show this widget
      </label>
      <textarea
        id="widget-origins"
        rows={4}
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="https://www.example.com"
        aria-describedby="widget-origins-hint"
        className="block w-full rounded-md border border-slate-300 bg-white px-3 py-2 font-mono text-sm"
      />
      <p id="widget-origins-hint" className="text-xs text-slate-500">
        One per line, for example https://shop.example.com. Each must match exactly: no paths and no wildcards. https
        is required except for localhost.
      </p>
      <Button type="submit" disabled={submitting}>
        {submitting ? "Saving…" : "Save allowed websites"}
      </Button>
    </form>
  );
}

function KeyCard({ widget, onRotated }: { widget: Widget; onRotated: () => void }) {
  const { workspace } = useWorkspace();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function rotate() {
    if (!window.confirm("Replace the widget key? Websites using the current key will stop working until updated.")) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api<Widget>(`/workspaces/${workspace.id}/widget/rotate-key`, { method: "POST" });
      onRotated();
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Embed on your website">
      <div className="space-y-3">
        <FormError error={error} />
        <p className="text-sm text-slate-700">
          Widget key: <code className="rounded bg-slate-100 px-1.5 py-0.5 text-xs">{widget.public_key}</code>
        </p>
        <p className="text-xs text-slate-500">
          This key identifies your widget. It is not a password: it only works from the websites you allow below.
        </p>
        {/* Focusable, so keyboard users can scroll a snippet wider than the page. */}
        <pre
          tabIndex={0}
          aria-label="Embed snippet"
          className="overflow-x-auto rounded-md bg-slate-900 p-3 text-xs text-slate-100"
        >
          <code>{embedSnippet(widget.loader_url, widget.public_key)}</code>
        </pre>
        <p className="text-xs text-slate-500">
          Paste this just before the closing body tag of your website. The chat button appears only on the websites listed
          under Allowed websites.
        </p>
        <Button variant="danger" onClick={() => void rotate()} disabled={busy}>
          {busy ? "Replacing…" : "Replace key"}
        </Button>
      </div>
    </Card>
  );
}

export function WidgetPage() {
  const { workspace } = useWorkspace();
  const path = `/workspaces/${workspace.id}/widget`;
  const { state, reload } = useAsync(() => api<Widget>(path), path);

  if (!canConfigureAssistant(workspace.role)) {
    return <Notice tone="error">You do not have permission to configure the widget.</Notice>;
  }
  return (
    <>
      <h1 className="text-xl font-bold">Chat widget</h1>
      {state.status === "loading" && <Loading label="Loading widget" />}
      {state.status === "error" && <ErrorState error={state.error} onRetry={reload} />}
      {state.status === "ready" && (
        <>
          <KeyCard widget={state.data} onRotated={reload} />
          <Card title="Allowed websites">
            <OriginsForm key={state.data.id} widget={state.data} onSaved={reload} />
          </Card>
          <Card title="Appearance">
            <AppearanceForm key={state.data.id} widget={state.data} onSaved={reload} />
          </Card>
        </>
      )}
    </>
  );
}
