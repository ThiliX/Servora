import { useState } from "react";
import {
  BookOpen,
  Bot,
  FlaskConical,
  Gauge,
  Inbox as InboxIcon,
  LayoutDashboard,
  LogOut,
  Menu,
  MessagesSquare,
  Monitor,
  Moon,
  PanelBottom,
  ScrollText,
  Settings as SettingsIcon,
  Sparkles,
  Sun,
  Users,
  type LucideIcon,
} from "lucide-react";
import { Link, Navigate, NavLink, Outlet, useNavigate, useOutletContext, useParams } from "react-router";
import { NotificationsBell } from "../components/NotificationsBell";
import { Badge, Button, ErrorState, Loading, Notice, Select } from "../components/ui";
import { api } from "../lib/api";
import { useAuth } from "../lib/auth";
import { useLiveDemo } from "../lib/liveDemo";
import {
  canConfigureAssistant,
  canManageSettings,
  canViewAnalytics,
  canViewAuditLog,
  canViewConversations,
  canViewUsage,
  ROLE_LABELS,
} from "../lib/permissions";
import { useTheme } from "../lib/theme";
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

/** The first page of a workspace: the Overview for those who can see metrics, the inbox for everyone else. */
export function WorkspaceHome() {
  const { workspace } = useWorkspace();
  return <Navigate to={canViewAnalytics(workspace.role) ? "overview" : "inbox"} replace />;
}

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  show: boolean;
}

interface NavGroup {
  heading?: string;
  items: NavItem[];
}

export function WorkspaceLayout() {
  const { workspaceId = "" } = useParams();
  const navigate = useNavigate();
  const { state: auth, logout } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const demo = useLiveDemo();
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
        ) : state.error.code === "WORKSPACE_SUSPENDED" ? (
          <Notice tone="error">This workspace is suspended. Contact Servora support to restore it.</Notice>
        ) : (
          <ErrorState error={state.error} onRetry={reload} />
        )}
        <Link to="/" className="text-sm font-medium text-accent-text underline">
          Back to your workspaces
        </Link>
      </main>
    );
  }

  const { workspace, all } = state.data;
  const role = workspace.role;
  const groups: NavGroup[] = [
    {
      items: [
        { to: "overview", label: "Overview", icon: LayoutDashboard, show: canViewAnalytics(role) },
        { to: "inbox", label: "Inbox", icon: InboxIcon, show: true },
        { to: "conversations", label: "Conversations", icon: MessagesSquare, show: canViewConversations(role) },
      ],
    },
    {
      heading: "Assistant",
      items: [
        { to: "assistant", label: "Assistant", icon: Bot, show: canConfigureAssistant(role) },
        { to: "knowledge", label: "Knowledge", icon: BookOpen, show: canConfigureAssistant(role) },
        { to: "playground", label: "Test", icon: FlaskConical, show: canConfigureAssistant(role) },
        { to: "widget", label: "Widget", icon: PanelBottom, show: canConfigureAssistant(role) },
      ],
    },
    {
      heading: "Workspace",
      items: [
        { to: "team", label: "Team", icon: Users, show: true },
        { to: "usage", label: "Usage & plan", icon: Gauge, show: canViewUsage(role) },
        { to: "audit", label: "Audit log", icon: ScrollText, show: canViewAuditLog(role) },
        { to: "settings", label: "Settings", icon: SettingsIcon, show: canManageSettings(role) },
      ],
    },
  ];
  const name = auth.user.display_name || auth.user.email;

  const sidebar = (
    <div className="flex h-full flex-col">
      <div className="flex h-16 shrink-0 items-center px-5">
        <Link to="/" className="flex items-center gap-2.5 rounded-lg" aria-label="Servora, your workspaces">
          <Logo />
          <span className="text-[15px] font-semibold tracking-tight text-fg">Servora</span>
        </Link>
      </div>
      <div className="space-y-2 px-3 pb-3">
        <Select
          id="workspace-switcher"
          label="Switch workspace"
          hideLabel
          value={workspace.id}
          onChange={(e) => navigate(`/w/${e.target.value}`)}
        >
          {all
            .filter((item) => item.status !== "SUSPENDED" || item.id === workspace.id)
            .map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
        </Select>
        <p className="flex items-center gap-1.5 px-1 text-xs text-fg-subtle">
          Your role <Badge tone="accent">{ROLE_LABELS[role]}</Badge>
        </p>
      </div>
      <nav aria-label="Workspace" className="flex-1 space-y-5 overflow-y-auto px-3 py-2">
        {groups.map((group, index) => {
          const items = group.items.filter((item) => item.show);
          if (items.length === 0) {
            return null;
          }
          return (
            <div key={group.heading ?? index}>
              {group.heading && (
                <p className="mb-1 px-2.5 text-[11px] font-semibold tracking-wider text-fg-subtle uppercase">{group.heading}</p>
              )}
              <ul className="space-y-0.5">
                {items.map((item) => (
                  <li key={item.to}>
                    <NavLink
                      to={item.to}
                      onClick={() => setMenuOpen(false)}
                      className={({ isActive }) =>
                        `flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm font-medium transition-colors ${
                          isActive ? "bg-accent-soft text-accent-text" : "text-fg-muted hover:bg-subtle hover:text-fg"
                        }`
                      }
                    >
                      <item.icon aria-hidden="true" className="size-4 shrink-0" />
                      {item.label}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </div>
          );
        })}
      </nav>
      <div className="space-y-3 border-t border-line p-3">
        <ThemeSwitcher />
        <div className="flex items-center gap-2.5 px-1">
          <span
            aria-hidden="true"
            className="flex size-8 shrink-0 items-center justify-center rounded-full bg-accent-soft text-xs font-semibold text-accent-text"
          >
            {initials(name)}
          </span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-fg">{name}</p>
            <p className="truncate text-xs text-fg-subtle">{auth.user.email}</p>
          </div>
        </div>
        <Button variant="ghost" size="sm" icon={LogOut} className="w-full justify-start" onClick={() => void logout()}>
          Sign out
        </Button>
      </div>
    </div>
  );

  return (
    <div className="min-h-screen lg:pl-64">
      {/* Fixed on large screens; a slide-over menu on small ones. */}
      <aside className="fixed inset-y-0 left-0 z-20 hidden w-64 border-r border-line bg-surface lg:block">{sidebar}</aside>
      {menuOpen && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="Navigation">
          <button
            type="button"
            aria-label="Close navigation"
            className="absolute inset-0 bg-black/40"
            onClick={() => setMenuOpen(false)}
          />
          <aside className="relative h-full w-72 max-w-[85vw] border-r border-line bg-surface shadow-raised">{sidebar}</aside>
        </div>
      )}
      <header className="sticky top-0 z-10 flex h-16 items-center gap-3 border-b border-line bg-surface/85 px-4 backdrop-blur sm:px-6 lg:px-8">
        <button
          type="button"
          aria-label="Open navigation"
          className="inline-flex size-9 items-center justify-center rounded-lg text-fg-muted hover:bg-subtle lg:hidden"
          onClick={() => setMenuOpen(true)}
        >
          <Menu aria-hidden="true" className="size-5" />
        </button>
        <p className="min-w-0 flex-1 truncate text-sm font-semibold text-fg">{workspace.name}</p>
        <NotificationsBell workspaceId={workspace.id} />
      </header>
      <main className="mx-auto max-w-6xl space-y-6 px-4 py-8 sm:px-6 lg:px-8">
        {demo && (
          <Notice tone="info">
            Public demo: other visitors share this account and see what you do, and everything resets {demo.reset}. Don't
            enter personal information. Some settings are locked.
            {demo.sites.length > 0 && (
              <>
                {" "}
                Chat as a customer:{" "}
                {demo.sites.map((site, index) => (
                  <span key={site.url}>
                    {index > 0 && " · "}
                    <a href={site.url} target="_blank" rel="noreferrer" className="font-medium text-accent-text underline">
                      {site.label}
                    </a>
                  </span>
                ))}
              </>
            )}
          </Notice>
        )}
        <Outlet context={{ workspace, user: auth.user, reloadWorkspace: reload } satisfies WorkspaceOutletContext} />
      </main>
    </div>
  );
}

function initials(name: string): string {
  const parts = name
    .replace(/@.*/, "")
    .split(/[\s._-]+/)
    .filter(Boolean);
  return ((parts[0]?.[0] ?? "") + (parts[1]?.[0] ?? "")).toUpperCase() || "?";
}

/** The Servora mark: a rounded square with a spark. */
export function Logo({ size = 28 }: { size?: number }) {
  return (
    <span
      aria-hidden="true"
      style={{ width: size, height: size }}
      className="flex items-center justify-center rounded-lg bg-gradient-to-br from-indigo-500 to-violet-600 text-white shadow-card"
    >
      <Sparkles className="size-4" />
    </span>
  );
}

/** Light, dark, or follow the system. */
export function ThemeSwitcher() {
  const { preference, setPreference } = useTheme();
  const options = [
    { value: "light", label: "Light", icon: Sun },
    { value: "dark", label: "Dark", icon: Moon },
    { value: "system", label: "System", icon: Monitor },
  ] as const;
  return (
    <div role="group" aria-label="Colour theme" className="grid grid-cols-3 gap-1 rounded-lg bg-subtle p-1">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={preference === option.value}
          onClick={() => setPreference(option.value)}
          className={`inline-flex items-center justify-center gap-1.5 rounded-md py-1.5 text-xs font-medium transition-colors ${
            preference === option.value ? "bg-surface text-fg shadow-card" : "text-fg-muted hover:text-fg"
          }`}
        >
          <option.icon aria-hidden="true" className="size-3.5" />
          {option.label}
        </button>
      ))}
    </div>
  );
}
