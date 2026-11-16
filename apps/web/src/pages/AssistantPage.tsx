import { useState, type FormEvent } from "react";
import { Button, Card, ErrorState, Field, FormError, Loading, Notice, Select } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import { canConfigureAssistant } from "../lib/permissions";
import type { Assistant, ConfidencePolicy, Tone } from "../lib/types";
import { toApiError, useAsync } from "../lib/useAsync";
import { useWorkspace } from "./WorkspaceLayout";

function TextArea({
  id,
  label,
  hint,
  value,
  onChange,
}: {
  id: string;
  label: string;
  hint?: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium text-slate-700">
        {label}
      </label>
      <textarea
        id={id}
        rows={3}
        maxLength={500}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-describedby={hint ? `${id}-hint` : undefined}
        className="mt-1 block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm"
      />
      {hint && (
        <p id={`${id}-hint`} className="mt-1 text-xs text-slate-500">
          {hint}
        </p>
      )}
    </div>
  );
}

function AssistantForm({ assistant, onSaved }: { assistant: Assistant; onSaved: () => void }) {
  const { workspace } = useWorkspace();
  const [name, setName] = useState(assistant.name);
  const [welcome, setWelcome] = useState(assistant.welcome_message);
  const [fallback, setFallback] = useState(assistant.fallback_message);
  const [language, setLanguage] = useState(assistant.language);
  const [tone, setTone] = useState<Tone>(assistant.tone);
  const [policy, setPolicy] = useState<ConfidencePolicy>(assistant.confidence_policy);
  const [automation, setAutomation] = useState(assistant.automation_enabled);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [savedVersion, setSavedVersion] = useState<number | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setSavedVersion(null);
    try {
      const saved = await api<Assistant>(`/workspaces/${workspace.id}/assistant`, {
        method: "PUT",
        body: {
          name,
          welcome_message: welcome,
          fallback_message: fallback,
          language,
          tone,
          automation_enabled: automation,
          confidence_policy: policy,
        },
      });
      setSavedVersion(saved.current_version);
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
      {savedVersion !== null && <Notice tone="success">Saved as version {savedVersion}.</Notice>}
      <Field id="assistant-name" label="Assistant name" required maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />
      <TextArea id="assistant-welcome" label="Welcome message" hint="The first thing a customer sees." value={welcome} onChange={setWelcome} />
      <TextArea
        id="assistant-fallback"
        label="Fallback message"
        hint="Used when the assistant cannot find an answer in your knowledge base."
        value={fallback}
        onChange={setFallback}
      />
      <div className="grid gap-4 sm:grid-cols-2">
        <Field id="assistant-language" label="Language" hint="A language code such as en or en-GB." value={language} onChange={(e) => setLanguage(e.target.value)} />
        <Select id="assistant-tone" label="Tone" value={tone} onChange={(e) => setTone(e.target.value as Tone)}>
          <option value="FRIENDLY">Friendly</option>
          <option value="PROFESSIONAL">Professional</option>
          <option value="CONCISE">Concise</option>
        </Select>
      </div>
      <Select id="assistant-policy" label="When evidence is thin" value={policy} onChange={(e) => setPolicy(e.target.value as ConfidencePolicy)}>
        <option value="STRICT">Hand off to a person (recommended)</option>
        <option value="BALANCED">Answer when reasonably supported</option>
      </Select>
      <label className="flex items-center gap-2 text-sm text-slate-700">
        <input type="checkbox" checked={automation} onChange={(e) => setAutomation(e.target.checked)} className="size-4" />
        Let the assistant reply to customers automatically
      </label>
      <Button type="submit" disabled={submitting || !name.trim() || !welcome.trim() || !fallback.trim()}>
        {submitting ? "Saving…" : "Save assistant"}
      </Button>
    </form>
  );
}

export function AssistantPage() {
  const { workspace } = useWorkspace();
  const path = `/workspaces/${workspace.id}/assistant`;
  const { state, reload } = useAsync(() => api<Assistant>(path), path);

  if (!canConfigureAssistant(workspace.role)) {
    return <Notice tone="error">You do not have permission to configure the assistant.</Notice>;
  }
  return (
    <>
      <h1 className="text-xl font-bold">AI assistant</h1>
      <Notice tone="info">
        The assistant does not answer customers yet. These settings take effect when answering and the chat widget are
        released.
      </Notice>
      <Card>
        {state.status === "loading" && <Loading label="Loading assistant" />}
        {state.status === "error" && <ErrorState error={state.error} onRetry={reload} />}
        {state.status === "ready" && (
          <>
            <p className="mb-4 text-sm text-slate-500">
              Current version {state.data.current_version}. Every save is kept as a new version.
            </p>
            <AssistantForm key={state.data.id} assistant={state.data} onSaved={reload} />
          </>
        )}
      </Card>
    </>
  );
}
