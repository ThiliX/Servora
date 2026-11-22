import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "react-router";
import { Loading, Notice } from "../components/ui";
import { api } from "../lib/api";
import { toApiError } from "../lib/useAsync";
import { AuthShell, tokenFromHash } from "./AuthShell";

type State = { status: "working" } | { status: "done" } | { status: "failed"; message: string };

export function VerifyEmailPage() {
  const location = useLocation();
  const token = tokenFromHash(location.hash);
  const [state, setState] = useState<State>(
    token ? { status: "working" } : { status: "failed", message: "This verification link is incomplete." },
  );
  // A verification token works once, so guard against React running the effect twice in development.
  const started = useRef(false);

  useEffect(() => {
    if (!token || started.current) {
      return;
    }
    started.current = true;
    api("/auth/verify-email", { method: "POST", body: { token }, anonymous: true })
      .then(() => setState({ status: "done" }))
      .catch((error: unknown) => {
        const apiError = toApiError(error);
        setState({
          status: "failed",
          message: apiError.isOffline ? "Cannot reach the server. Reload this page to try again." : apiError.message,
        });
      });
  }, [token]);

  return (
    <AuthShell title="Email verification">
      {state.status === "working" && <Loading label="Verifying your email" />}
      {state.status === "done" && <Notice tone="success">Your email is verified. You can sign in now.</Notice>}
      {state.status === "failed" && <Notice tone="error">{state.message}</Notice>}
      {state.status !== "working" && (
        <p className="mt-4 text-center text-sm">
          <Link to="/login" className="font-medium text-accent-text underline">
            Go to sign in
          </Link>
        </p>
      )}
    </AuthShell>
  );
}
