import { useState } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { Button, FormError, Notice } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import { useAuth } from "../lib/auth";
import { toApiError } from "../lib/useAsync";
import { AuthShell, tokenFromHash } from "./AuthShell";

export function AcceptInvitationPage() {
  const { state, logout } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const token = tokenFromHash(location.hash);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  // Joining is an explicit action, not something that happens just by opening a link.
  async function accept() {
    setSubmitting(true);
    setError(null);
    try {
      const result = await api<{ workspace_id: string }>("/invitations/accept", { method: "POST", body: { token } });
      navigate(`/w/${result.workspace_id}/team`, { replace: true });
    } catch (caught) {
      setError(toApiError(caught));
      setSubmitting(false);
    }
  }

  if (!token) {
    return (
      <AuthShell title="Workspace invitation">
        <Notice tone="error">This invitation link is incomplete.</Notice>
      </AuthShell>
    );
  }

  const email = state.status === "authenticated" ? state.user.email : "";
  return (
    <AuthShell title="Workspace invitation">
      <div className="space-y-4">
        <p className="text-sm text-fg">
          You are signed in as <strong>{email}</strong>. Accept the invitation to join the workspace.
        </p>
        <FormError error={error} />
        {error?.code === "INVITATION_EMAIL_MISMATCH" && (
          <Button variant="secondary" className="w-full" onClick={() => void logout()}>
            Sign in with a different account
          </Button>
        )}
        <Button className="w-full" onClick={() => void accept()} disabled={submitting}>
          {submitting ? "Joining…" : "Accept invitation"}
        </Button>
        <p className="text-center text-sm">
          <Link to="/" className="font-medium text-accent-text underline">
            Not now
          </Link>
        </p>
      </div>
    </AuthShell>
  );
}
