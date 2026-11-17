import { useState, type FormEvent } from "react";
import { Button, Card, Field, FormError, Notice } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import { canManageData, canManageSettings } from "../lib/permissions";
import type { Workspace } from "../lib/types";
import { toApiError } from "../lib/useAsync";
import { useWorkspace } from "./WorkspaceLayout";

export function SettingsPage() {
  const { workspace, reloadWorkspace } = useWorkspace();
  const [name, setName] = useState(workspace.name);
  const [timezone, setTimezone] = useState(workspace.timezone);
  const [language, setLanguage] = useState(workspace.default_language);
  const [industry, setIndustry] = useState(workspace.industry ?? "");
  const [logoUrl, setLogoUrl] = useState(workspace.logo_url ?? "");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [saved, setSaved] = useState(false);

  if (!canManageSettings(workspace.role)) {
    return <Notice tone="error">You do not have permission to change workspace settings.</Notice>;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setSaved(false);
    try {
      await api<Workspace>(`/workspaces/${workspace.id}`, {
        method: "PATCH",
        body: { name, timezone, default_language: language, industry, logo_url: logoUrl },
      });
      setSaved(true);
      reloadWorkspace();
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <h1 className="text-xl font-bold">Workspace settings</h1>
      <Card>
        <form onSubmit={submit} className="max-w-lg space-y-4" noValidate>
          <FormError error={error} />
          {saved && <Notice tone="success">Settings saved.</Notice>}
          <Field id="settings-name" label="Business name" required maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
          <Field id="settings-slug" label="Workspace address" value={workspace.slug} disabled readOnly />
          <Field
            id="settings-timezone"
            label="Timezone"
            hint="An IANA zone, for example Asia/Colombo or Europe/London."
            value={timezone}
            onChange={(e) => setTimezone(e.target.value)}
          />
          <Field
            id="settings-language"
            label="Default language"
            hint="A language code such as en or en-GB."
            value={language}
            onChange={(e) => setLanguage(e.target.value)}
          />
          <Field id="settings-industry" label="Industry" maxLength={100} value={industry} onChange={(e) => setIndustry(e.target.value)} />
          <Field
            id="settings-logo"
            label="Logo URL"
            type="url"
            hint="An https link to your logo. Uploading a logo file arrives with document storage."
            value={logoUrl}
            onChange={(e) => setLogoUrl(e.target.value)}
          />
          <Button type="submit" disabled={submitting || !name.trim()}>
            {submitting ? "Saving…" : "Save settings"}
          </Button>
        </form>
      </Card>
      {canManageData(workspace.role) && <DataControls workspace={workspace} onSaved={reloadWorkspace} />}
    </>
  );
}

/** Owner-only: how long conversations are kept, and a full export of the workspace's data. */
function DataControls({ workspace, onSaved }: { workspace: Workspace; onSaved: () => void }) {
  const [days, setDays] = useState(workspace.conversation_retention_days?.toString() ?? "");
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<ApiError | null>(null);
  const parsed = days.trim() === "" ? null : Number(days);
  const valid = parsed === null || (Number.isInteger(parsed) && parsed >= 30 && parsed <= 3650);

  async function saveRetention(event: FormEvent) {
    event.preventDefault();
    setSaving(true);
    setSaved(false);
    setError(null);
    try {
      await api(`/workspaces/${workspace.id}/retention`, { method: "PUT", body: { conversation_retention_days: parsed } });
      setSaved(true);
      onSaved();
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setSaving(false);
    }
  }

  async function download() {
    setExporting(true);
    setExportError(null);
    try {
      const data = await api<unknown>(`/workspaces/${workspace.id}/export`);
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `servora-export-${workspace.slug}-${new Date().toISOString().slice(0, 10)}.json`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (caught) {
      setExportError(toApiError(caught));
    } finally {
      setExporting(false);
    }
  }

  return (
    <Card title="Data and privacy">
      <form onSubmit={saveRetention} className="max-w-lg space-y-3" noValidate>
        <FormError error={error} />
        {saved && <Notice tone="success">Retention period saved.</Notice>}
        <Field
          id="settings-retention"
          label="Delete conversations after (days)"
          inputMode="numeric"
          hint="Between 30 and 3650 days after the last message. Leave empty to keep conversations. Conversations with an open ticket are kept until it is resolved. Deletion cannot be undone."
          value={days}
          onChange={(e) => setDays(e.target.value)}
        />
        <Button type="submit" disabled={saving || !valid}>
          {saving ? "Saving…" : "Save retention period"}
        </Button>
      </form>
      <div className="mt-6 max-w-lg space-y-2 border-t border-slate-200 pt-4">
        <h3 className="text-sm font-semibold">Export workspace data</h3>
        <p className="text-sm text-slate-600">
          Downloads a JSON file with your settings, team, configuration, document list, conversations, tickets, notes,
          ratings, audit log and monthly usage. Document files themselves are not included.
        </p>
        <FormError error={exportError} />
        <Button variant="secondary" disabled={exporting} onClick={() => void download()}>
          {exporting ? "Preparing…" : "Download export"}
        </Button>
      </div>
    </Card>
  );
}
