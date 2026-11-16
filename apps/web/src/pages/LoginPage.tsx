import { useState, type FormEvent } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router";
import { Button, Field, FormError, Loading } from "../components/ui";
import type { ApiError } from "../lib/api";
import { useAuth } from "../lib/auth";
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

  if (state.status === "loading") {
    return <Loading label="Restoring your session" />;
  }
  if (state.status === "authenticated") {
    return <Navigate to={returnTo} replace />;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(email, password);
      navigate(returnTo, { replace: true });
    } catch (caught) {
      setError(toApiError(caught));
      setSubmitting(false);
    }
  }

  return (
    <AuthShell title="Sign in">
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
      <p className="mt-4 text-center text-sm text-slate-600">
        New to Servora?{" "}
        <Link to="/register" className="font-medium text-indigo-700 underline">
          Create an account
        </Link>
      </p>
    </AuthShell>
  );
}
