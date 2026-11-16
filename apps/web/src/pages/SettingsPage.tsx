import { useState, type FormEvent } from "react";
import { Button, Card, Field, FormError, Notice } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import { canManageSettings } from "../lib/permissions";
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
    </>
  );
}
