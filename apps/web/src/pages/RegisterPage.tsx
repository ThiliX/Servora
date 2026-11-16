import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { Button, Field, FormError, Notice } from "../components/ui";
import { api, type ApiError } from "../lib/api";
import { toApiError } from "../lib/useAsync";
import { AuthShell } from "./AuthShell";

const MIN_PASSWORD_LENGTH = 10;

export function RegisterPage() {
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [submitted, setSubmitted] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await api("/auth/register", {
        method: "POST",
        body: { email, password, display_name: displayName },
        anonymous: true,
      });
      setSubmitted(true);
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setSubmitting(false);
    }
  }

  if (submitted) {
    return (
      <AuthShell title="Check your email">
        <Notice tone="success">
          If that address can be registered, we have sent a verification link to it. Open the link to activate your
          account, then sign in.
        </Notice>
        <p className="mt-4 text-center text-sm">
          <Link to="/login" className="font-medium text-indigo-700 underline">
            Back to sign in
          </Link>
        </p>
      </AuthShell>
    );
  }

  const valid = displayName.trim().length > 0 && email.length > 0 && password.length >= MIN_PASSWORD_LENGTH;
  return (
    <AuthShell title="Create your account">
      <form onSubmit={submit} className="space-y-4" noValidate>
        <FormError error={error} />
        <Field
          id="display-name"
          label="Your name"
          autoComplete="name"
          required
          maxLength={100}
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
        />
        <Field
          id="email"
          label="Work email"
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
          autoComplete="new-password"
          required
          minLength={MIN_PASSWORD_LENGTH}
          hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Button type="submit" className="w-full" disabled={submitting || !valid}>
          {submitting ? "Creating account…" : "Create account"}
        </Button>
      </form>
      <p className="mt-4 text-center text-sm text-slate-600">
        Already have an account?{" "}
        <Link to="/login" className="font-medium text-indigo-700 underline">
          Sign in
        </Link>
      </p>
    </AuthShell>
  );
}
