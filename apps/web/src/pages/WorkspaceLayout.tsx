import { Link, NavLink, Outlet, useNavigate, useOutletContext, useParams } from "react-router";
import { Button, ErrorState, Loading, Notice, Select } from "../components/ui";
import { api } from "../lib/api";
import { useAuth } from "../lib/auth";
import { canConfigureAssistant, canManageSettings, ROLE_LABELS } from "../lib/permissions";
import type { User, Workspace, WorkspaceSummary } from "../lib/types";
import { useAsync } from "../lib/useAsync";

export interface WorkspaceOutletContext {
  workspace: Workspace;
  user: User;
  reloadWorkspace: () => void;
}

export function useWorkspace(): WorkspaceOutletContext {
  return useOutletContext<WorkspaceOutletContext>();
}

export function WorkspaceLayout() {
  const { workspaceId = "" } = useParams();
  const navigate = useNavigate();
  const { state: auth, logout } = useAuth();
  // The role shown and used here comes from the server for this workspace on every load.
  const { state, reload } = useAsync(
    async () => {
      const [workspace, all] = await Promise.all([
        api<Workspace>(`/workspaces/${workspaceId}`),
        api<WorkspaceSummary[]>("/workspaces"),
      ]);
      return { workspace, all };
    },
    workspaceId,
  );

  if (auth.status !== "authenticated") {
    return null;
  }
  if (state.status === "loading") {
    return <Loading label="Loading workspace" />;
  }
  if (state.status === "error") {
    return (
      <main className="mx-auto max-w-3xl space-y-4 px-4 py-10">
        {state.error.isNotFound ? (
          <Notice tone="error">This workspace does not exist, or you are not a member of it.</Notice>
        ) : (
          <ErrorState error={state.error} onRetry={reload} />
        )}
        <Link to="/" className="text-sm font-medium text-indigo-700 underline">
          Back to your workspaces
        </Link>
      </main>
    );
  }

  const { workspace, all } = state.data;
  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `rounded-md px-3 py-2 text-sm font-medium ${isActive ? "bg-indigo-50 text-indigo-700" : "text-slate-700 hover:bg-slate-100"}`;

  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <div className="flex items-center gap-3">
            <Link to="/" className="text-lg font-bold text-indigo-700">
              Servora
            </Link>
            <Select
              id="workspace-switcher"
              label="Switch workspace"
              hideLabel
              value={workspace.id}
              onChange={(e) => navigate(`/w/${e.target.value}/team`)}
            >
              {all.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name}
                </option>
              ))}
            </Select>
            <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-700">
              {ROLE_LABELS[workspace.role]}
            </span>
          </div>
          <div className="flex items-center gap-3 text-sm">
            <span className="text-slate-600">{auth.user.email}</span>
            <Button variant="secondary" onClick={() => void logout()}>
              Sign out
            </Button>
          </div>
        </div>
        <nav aria-label="Workspace" className="mx-auto flex max-w-5xl flex-wrap gap-1 px-4 pb-2">
          <NavLink to="team" className={linkClass}>
            Team
          </NavLink>
          {canConfigureAssistant(workspace.role) && (
            <>
              <NavLink to="assistant" className={linkClass}>
                Assistant
              </NavLink>
              <NavLink to="knowledge" className={linkClass}>
                Knowledge
              </NavLink>
              <NavLink to="playground" className={linkClass}>
                Test
              </NavLink>
              <NavLink to="widget" className={linkClass}>
                Widget
              </NavLink>
            </>
          )}
          {canManageSettings(workspace.role) && (
            <NavLink to="settings" className={linkClass}>
              Settings
            </NavLink>
          )}
        </nav>
      </header>
      <main className="mx-auto max-w-5xl space-y-6 px-4 py-6">
        <Outlet context={{ workspace, user: auth.user, reloadWorkspace: reload } satisfies WorkspaceOutletContext} />
      </main>
    </div>
  );
}
