import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from "react";
import type { ApiError } from "../lib/api";

export function Button({
  variant = "primary",
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "danger" }) {
  const styles = {
    primary: "bg-indigo-600 text-white hover:bg-indigo-700",
    secondary: "border border-slate-300 bg-white text-slate-800 hover:bg-slate-100",
    danger: "border border-red-300 bg-white text-red-700 hover:bg-red-50",
  }[variant];
  return (
    <button
      type="button"
      {...props}
      className={`rounded-md px-3 py-2 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-50 ${styles} ${className}`}
    />
  );
}

export function Field({
  label,
  hint,
  id,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & { label: string; hint?: string; id: string }) {
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium text-slate-700">
        {label}
      </label>
      <input
        id={id}
        aria-describedby={hint ? `${id}-hint` : undefined}
        {...props}
        className="mt-1 block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm disabled:bg-slate-100"
      />
      {hint && (
        <p id={`${id}-hint`} className="mt-1 text-xs text-slate-500">
          {hint}
        </p>
      )}
    </div>
  );
}

export function Select({
  label,
  id,
  children,
  hideLabel = false,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement> & { label: string; id: string; hideLabel?: boolean; children: ReactNode }) {
  return (
    <div>
      <label htmlFor={id} className={hideLabel ? "sr-only" : "block text-sm font-medium text-slate-700"}>
        {label}
      </label>
      <select
        id={id}
        {...props}
        className={`block rounded-md border border-slate-300 bg-white px-2 py-2 text-sm disabled:bg-slate-100 ${hideLabel ? "" : "mt-1 w-full"}`}
      >
        {children}
      </select>
    </div>
  );
}

export function Card({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
      {title && <h2 className="mb-4 text-base font-semibold">{title}</h2>}
      {children}
    </section>
  );
}

export function Notice({ tone, children }: { tone: "error" | "success" | "info"; children: ReactNode }) {
  const styles = {
    error: "border-red-200 bg-red-50 text-red-800",
    success: "border-emerald-200 bg-emerald-50 text-emerald-800",
    info: "border-slate-200 bg-slate-100 text-slate-700",
  }[tone];
  return (
    <div role={tone === "error" ? "alert" : "status"} className={`rounded-md border px-3 py-2 text-sm ${styles}`}>
      {children}
    </div>
  );
}

export function Loading({ label = "Loading" }: { label?: string }) {
  return (
    <p role="status" className="py-6 text-center text-sm text-slate-500">
      {label}…
    </p>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-6 text-center text-sm text-slate-500">{children}</p>;
}

/** Explains a failed load: offline, no permission, not found, or a generic error with a retry. */
export function ErrorState({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  let message = error.message;
  if (error.isOffline) {
    message = "You appear to be offline, or the server cannot be reached.";
  } else if (error.isForbidden) {
    message = "You do not have permission to view this.";
  }
  return (
    <div role="alert" className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
      <p>{message}</p>
      {error.traceId && <p className="mt-1 text-xs text-red-700">Reference: {error.traceId}</p>}
      {onRetry && !error.isForbidden && !error.isNotFound && (
        <Button variant="secondary" className="mt-3" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}

/** Inline error for a failed form submission, including per-field messages. */
export function FormError({ error }: { error: ApiError | null }) {
  if (!error) {
    return null;
  }
  return (
    <Notice tone="error">
      <p>{error.isOffline ? "Cannot reach the server. Check your connection and try again." : error.message}</p>
      {error.details.length > 0 && (
        <ul className="mt-1 list-disc pl-5">
          {error.details.map((detail) => (
            <li key={detail.field + detail.message}>
              {detail.field.replaceAll("_", " ")}: {detail.message}
            </li>
          ))}
        </ul>
      )}
    </Notice>
  );
}
