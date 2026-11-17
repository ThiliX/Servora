import type { ReactNode } from "react";
import { Navigate, Route, Routes, useLocation } from "react-router";
import { Loading } from "./components/ui";
import { useAuth } from "./lib/auth";
import { AcceptInvitationPage } from "./pages/AcceptInvitationPage";
import { AssistantPage } from "./pages/AssistantPage";
import { AuditLogPage } from "./pages/AuditLogPage";
import { ConversationPage, ConversationsPage } from "./pages/ConversationsPage";
import { InboxPage } from "./pages/InboxPage";
import { KnowledgePage } from "./pages/KnowledgePage";
import { PlaygroundPage } from "./pages/PlaygroundPage";
import { LoginPage } from "./pages/LoginPage";
import { OverviewPage } from "./pages/OverviewPage";
import { PlatformPage } from "./pages/PlatformPage";
import { RegisterPage } from "./pages/RegisterPage";
import { SettingsPage } from "./pages/SettingsPage";
import { TeamPage } from "./pages/TeamPage";
import { UsagePage } from "./pages/UsagePage";
import { VerifyEmailPage } from "./pages/VerifyEmailPage";
import { WidgetPage } from "./pages/WidgetPage";
import { WorkspaceHome, WorkspaceLayout } from "./pages/WorkspaceLayout";
import { WorkspacesPage } from "./pages/WorkspacesPage";

/** Sends signed-out visitors to the login page and brings them back afterwards. */
function RequireAuth({ children }: { children: ReactNode }) {
  const { state } = useAuth();
  const location = useLocation();
  if (state.status === "loading") {
    return <Loading label="Restoring your session" />;
  }
  if (state.status === "anonymous") {
    const returnTo = location.pathname + location.search + location.hash;
    return <Navigate to="/login" replace state={{ returnTo }} />;
  }
  return children;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/verify-email" element={<VerifyEmailPage />} />
      <Route
        path="/accept-invitation"
        element={
          <RequireAuth>
            <AcceptInvitationPage />
          </RequireAuth>
        }
      />
      <Route
        path="/"
        element={
          <RequireAuth>
            <WorkspacesPage />
          </RequireAuth>
        }
      />
      <Route
        path="/w/:workspaceId"
        element={
          <RequireAuth>
            <WorkspaceLayout />
          </RequireAuth>
        }
      >
        <Route index element={<WorkspaceHome />} />
        <Route path="overview" element={<OverviewPage />} />
        <Route path="usage" element={<UsagePage />} />
        <Route path="audit" element={<AuditLogPage />} />
        <Route path="team" element={<TeamPage />} />
        <Route path="inbox" element={<InboxPage />} />
        <Route path="inbox/:ticketId" element={<InboxPage />} />
        <Route path="conversations" element={<ConversationsPage />} />
        <Route path="conversations/:conversationId" element={<ConversationPage />} />
        <Route path="assistant" element={<AssistantPage />} />
        <Route path="knowledge" element={<KnowledgePage />} />
        <Route path="playground" element={<PlaygroundPage />} />
        <Route path="widget" element={<WidgetPage />} />
        <Route path="settings" element={<SettingsPage />} />
      </Route>
      <Route
        path="/platform"
        element={
          <RequireAuth>
            <PlatformPage />
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
