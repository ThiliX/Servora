import { useState, type FormEvent } from "react";
import { ChevronRight, LogOut } from "lucide-react";
import { Link, useNavigate } from "react-router";
import { Badge, Button, Card, Empty, ErrorState, Field, FormError, Loading } from "../components/ui";
import { Logo } from "./WorkspaceLayout";
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
      navigate(`/w/${workspace.id}`);
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
  // Only decides whether to show the link; the platform endpoints check access themselves.
  const platform = useAsync(
    () => api<{ platform_admin: boolean }>("/platform/access").catch(() => ({ platform_admin: false })),
    "platform-access",
  );

  return (
    <div className="min-h-screen">
      <header className="border-b border-line bg-surface">
        <div className="mx-auto flex h-16 max-w-3xl items-center justify-between gap-3 px-4">
          <span className="flex items-center gap-2.5">
            <Logo />
            <span className="text-[15px] font-semibold tracking-tight">Servora</span>
          </span>
          <div className="flex items-center gap-3 text-sm">
            {platform.state.status === "ready" && platform.state.data.platform_admin && (
              <Link to="/platform" className="font-medium text-accent-text hover:underline">
                Platform admin
              </Link>
            )}
            {auth.status === "authenticated" && <span className="hidden text-fg-muted sm:inline">{auth.user.email}</span>}
            <Button variant="secondary" size="sm" icon={LogOut} onClick={() => void logout()}>
              Sign out
            </Button>
          </div>
        </div>
      </header>
    <main className="mx-auto max-w-3xl space-y-6 px-4 py-10">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-fg">Your workspaces</h1>
        <p className="mt-1 text-sm text-fg-muted">Each workspace is one business, with its own assistant, documents and team.</p>
      </div>

      <Card>
        {state.status === "loading" && <Loading label="Loading workspaces" />}
        {state.status === "error" && <ErrorState error={state.error} onRetry={reload} />}
        {state.status === "ready" && state.data.length === 0 && (
          <Empty>You are not a member of any workspace yet. Create one below, or open an invitation link.</Empty>
        )}
        {state.status === "ready" && state.data.length > 0 && (
          <ul className="space-y-1">
            {state.data.map((workspace) =>
              workspace.status === "SUSPENDED" ? (
                <li key={workspace.id} className="px-3 py-3">
                  <div className="flex items-center justify-between">
                    <span>
                      <span className="font-medium text-fg-subtle">{workspace.name}</span>
                      <span className="ml-2 text-sm text-fg-subtle">{workspace.slug}</span>
                    </span>
                    <Badge tone="warning">Suspended</Badge>
                  </div>
                  <p className="mt-1 text-sm text-fg-muted">
                    This workspace is suspended
                    {workspace.suspended_reason ? `: ${workspace.suspended_reason}` : "."} Contact Servora support to restore it.
                  </p>
                </li>
              ) : (
                <li key={workspace.id}>
                  <Link
                    to={`/w/${workspace.id}`}
                    className="group flex items-center gap-3 rounded-lg px-3 py-3 transition-colors hover:bg-subtle"
                  >
                    <span
                      aria-hidden="true"
                      className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-accent-soft text-sm font-semibold text-accent-text"
                    >
                      {workspace.name.slice(0, 1).toUpperCase()}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block font-medium">{workspace.name}</span>
                      <span className="block text-sm text-fg-subtle">{workspace.slug}</span>
                    </span>
                    <Badge>{ROLE_LABELS[workspace.role]}</Badge>
                    <ChevronRight aria-hidden="true" className="size-4 text-fg-subtle transition-transform group-hover:translate-x-0.5" />
                  </Link>
                </li>
              ),
            )}
          </ul>
        )}
      </Card>

      <Card title="Create a workspace" description="You will be its owner. You can invite your team afterwards.">
        <CreateWorkspaceForm />
      </Card>
    </main>
    </div>
  );
}
