import { useState, type FormEvent } from "react";
import { CirclePlay } from "lucide-react";
import { Link, Navigate, useLocation, useNavigate } from "react-router";
import { Button, Field, FormError, Loading } from "../components/ui";
import type { ApiError } from "../lib/api";
import { useAuth } from "../lib/auth";
import { useLiveDemo } from "../lib/liveDemo";
import { toApiError } from "../lib/useAsync";
import { AuthShell } from "./AuthShell";

function safeReturnTo(state: unknown): string {
  const returnTo = (state as { returnTo?: unknown } | null)?.returnTo;
  // Only in-app paths: never redirect to another origin after sign-in.
  return typeof returnTo === "string" && returnTo.startsWith("/") && !returnTo.startsWith("//") ? returnTo : "/";
}

export function LoginPage() {
  const { state, login } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const returnTo = safeReturnTo(location.state);
  const demo = useLiveDemo();

  if (state.status === "loading") {
    return <Loading label="Restoring your session" />;
  }
  if (state.status === "authenticated") {
    return <Navigate to={returnTo} replace />;
  }

  async function signIn(withEmail: string, withPassword: string) {
    setSubmitting(true);
    setError(null);
    try {
      await login(withEmail, withPassword);
      navigate(returnTo, { replace: true });
    } catch (caught) {
      setError(toApiError(caught));
      setSubmitting(false);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    void signIn(email, password);
  }

  return (
    <AuthShell title="Sign in">
      {demo && (
        <section aria-labelledby="live-demo-heading" className="mb-6 rounded-xl border border-accent/30 bg-accent-soft p-4">
          <h2 id="live-demo-heading" className="flex items-center gap-2 text-sm font-semibold text-accent-text">
            <CirclePlay aria-hidden="true" className="size-4" />
            Try the live demo
          </h2>
          <p className="mt-1 text-xs text-fg-muted">
            Shared demo accounts with sample data, reset {demo.reset}. Please don't enter personal information.
          </p>
          <div className="mt-3 grid gap-2">
            {demo.accounts.map((account) => (
              <Button
                key={account.email}
                variant="secondary"
                className="w-full justify-start"
                disabled={submitting}
                onClick={() => void signIn(account.email, account.password)}
              >
                Sign in as {account.label}
              </Button>
            ))}
          </div>
          {demo.sites.length > 0 && (
            <p className="mt-3 text-xs text-fg-muted">
              Chat as a customer:{" "}
              {demo.sites.map((site, index) => (
                <span key={site.url}>
                  {index > 0 && " · "}
                  <a href={site.url} target="_blank" rel="noreferrer" className="font-medium text-accent-text underline">
                    {site.label}
                  </a>
                </span>
              ))}
            </p>
          )}
        </section>
      )}
      <form onSubmit={submit} className="space-y-4" noValidate>
        <FormError error={error} />
        <Field
          id="email"
          label="Email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <Field
          id="password"
          label="Password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Button type="submit" className="w-full" disabled={submitting || !email || !password}>
          {submitting ? "Signing in…" : "Sign in"}
        </Button>
      </form>
      {!demo && (
        <p className="mt-4 text-center text-sm text-fg-muted">
          New to Servora?{" "}
          <Link to="/register" className="font-medium text-accent-text underline">
            Create an account
          </Link>
        </p>
      )}
    </AuthShell>
  );
}
