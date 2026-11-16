import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { Button, Card, Empty, ErrorState, Field, FormError, Loading } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import { useAuth } from "../lib/auth";
import { ROLE_LABELS } from "../lib/permissions";
import type { Workspace, WorkspaceSummary } from "../lib/types";
import { toApiError, useAsync } from "../lib/useAsync";

/** Turns a workspace name into a URL-safe address the user can still edit. */
export function suggestSlug(name: string): string {
  return name
    .toLowerCase()
    .normalize("NFKD")
    .replace(/\p{M}/gu, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 50);
}

const SLUG_PATTERN = /^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$/;

function CreateWorkspaceForm() {
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [slugEdited, setSlugEdited] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const effectiveSlug = slugEdited ? slug : suggestSlug(name);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      const workspace = await api<Workspace>("/workspaces", {
        method: "POST",
        body: { name, slug: effectiveSlug, timezone },
      });
      navigate(`/w/${workspace.id}/team`);
    } catch (caught) {
      setError(toApiError(caught));
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4" noValidate>
      <FormError error={error} />
      <Field
        id="workspace-name"
        label="Business name"
        required
        maxLength={100}
        value={name}
        onChange={(e) => setName(e.target.value)}
      />
      <Field
        id="workspace-slug"
        label="Workspace address"
        required
        hint="3 to 50 lowercase letters, digits or hyphens. It cannot be changed later."
        value={effectiveSlug}
        onChange={(e) => {
          setSlugEdited(true);
          setSlug(e.target.value.toLowerCase());
        }}
      />
      <Button type="submit" disabled={submitting || !name.trim() || !SLUG_PATTERN.test(effectiveSlug)}>
        {submitting ? "Creating…" : "Create workspace"}
      </Button>
    </form>
  );
}

export function WorkspacesPage() {
  const { state: auth, logout } = useAuth();
  const { state, reload } = useAsync(() => api<WorkspaceSummary[]>("/workspaces"), "workspaces");

  return (
    <main className="mx-auto max-w-3xl space-y-6 px-4 py-10">
      <header className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Your workspaces</h1>
        <div className="flex items-center gap-3 text-sm">
          {auth.status === "authenticated" && <span className="text-slate-600">{auth.user.email}</span>}
          <Button variant="secondary" onClick={() => void logout()}>
            Sign out
          </Button>
        </div>
      </header>

      <Card>
        {state.status === "loading" && <Loading label="Loading workspaces" />}
        {state.status === "error" && <ErrorState error={state.error} onRetry={reload} />}
        {state.status === "ready" && state.data.length === 0 && (
          <Empty>You are not a member of any workspace yet. Create one below, or open an invitation link.</Empty>
        )}
        {state.status === "ready" && state.data.length > 0 && (
          <ul className="divide-y divide-slate-200">
            {state.data.map((workspace) => (
              <li key={workspace.id}>
                <Link
                  to={`/w/${workspace.id}/team`}
                  className="flex items-center justify-between rounded px-2 py-3 hover:bg-slate-50"
                >
                  <span>
                    <span className="font-medium">{workspace.name}</span>
                    <span className="ml-2 text-sm text-slate-500">{workspace.slug}</span>
                  </span>
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-700">
                    {ROLE_LABELS[workspace.role]}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card title="Create a workspace">
        <CreateWorkspaceForm />
      </Card>
    </main>
  );
}
