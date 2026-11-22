import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from "react";
import { AlertCircle, CheckCircle2, ChevronDown, Info, Inbox, Loader2, type LucideIcon } from "lucide-react";
import type { ApiError } from "../lib/api";

/** Shared look of text inputs, text areas and selects, for controls a page renders itself. */
export const inputClass =
  "block w-full rounded-lg border border-line-strong bg-surface px-3 py-2 text-sm text-fg shadow-card placeholder:text-fg-subtle transition-colors focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25 disabled:cursor-not-allowed disabled:bg-subtle disabled:text-fg-subtle";

type ButtonVariant = "primary" | "secondary" | "danger" | "ghost";

export function Button({
  variant = "primary",
  size = "md",
  icon: Icon,
  className = "",
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: "sm" | "md"; icon?: LucideIcon }) {
  const styles = {
    primary: "bg-accent text-on-accent shadow-card hover:bg-accent-hover",
    secondary: "border border-line-strong bg-surface text-fg shadow-card hover:bg-subtle",
    danger: "border border-danger-line bg-surface text-danger-text shadow-card hover:bg-danger-soft",
    ghost: "text-fg-muted hover:bg-subtle hover:text-fg",
  }[variant];
  const sizes = size === "sm" ? "h-8 gap-1.5 px-2.5 text-xs" : "h-9 gap-2 px-3.5 text-sm";
  return (
    <button
      type="button"
      {...props}
      className={`inline-flex shrink-0 items-center justify-center rounded-lg font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${sizes} ${styles} ${className}`}
    >
      {Icon && <Icon aria-hidden="true" className={size === "sm" ? "size-3.5" : "size-4"} />}
      {children}
    </button>
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
      <label htmlFor={id} className="block text-sm font-medium text-fg">
        {label}
      </label>
      <input id={id} aria-describedby={hint ? `${id}-hint` : undefined} {...props} className={`mt-1.5 ${inputClass}`} />
      {hint && (
        <p id={`${id}-hint`} className="mt-1.5 text-xs text-fg-subtle">
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
      <label htmlFor={id} className={hideLabel ? "sr-only" : "block text-sm font-medium text-fg"}>
        {label}
      </label>
      <div className={`relative ${hideLabel ? "" : "mt-1.5"}`}>
        <select id={id} {...props} className={`${inputClass} cursor-pointer appearance-none pr-9`}>
          {children}
        </select>
        <ChevronDown aria-hidden="true" className="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-fg-subtle" />
      </div>
    </div>
  );
}

export function Card({
  title,
  description,
  actions,
  children,
  className = "",
}: {
  title?: string;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`rounded-xl border border-line bg-surface p-5 shadow-card sm:p-6 ${className}`}>
      {(title || actions) && (
        <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
          <div>
            {title && <h2 className="text-base font-semibold tracking-tight text-fg">{title}</h2>}
            {description && <p className="mt-1 text-sm text-fg-muted">{description}</p>}
          </div>
          {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

/** The title row of a page: heading, a short description and the page's main actions. */
export function PageHeader({ title, description, actions }: { title: string; description?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        <h1 className="text-2xl font-semibold tracking-tight text-fg">{title}</h1>
        {description && <p className="mt-1 max-w-2xl text-sm text-fg-muted">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

export type BadgeTone = "neutral" | "accent" | "success" | "warning" | "danger";

export function Badge({ tone = "neutral", children, className = "" }: { tone?: BadgeTone; children: ReactNode; className?: string }) {
  const styles = {
    neutral: "bg-subtle text-fg-muted ring-line",
    accent: "bg-accent-soft text-accent-text ring-accent/20",
    success: "bg-success-soft text-success-text ring-success-line",
    warning: "bg-warning-soft text-warning-text ring-warning-line",
    danger: "bg-danger-soft text-danger-text ring-danger-line",
  }[tone];
  return (
    <span className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${styles} ${className}`}>
      {children}
    </span>
  );
}

export function Notice({ tone, children }: { tone: "error" | "success" | "info"; children: ReactNode }) {
  const config = {
    error: { styles: "border-danger-line bg-danger-soft text-danger-text", Icon: AlertCircle },
    success: { styles: "border-success-line bg-success-soft text-success-text", Icon: CheckCircle2 },
    info: { styles: "border-line bg-subtle text-fg-muted", Icon: Info },
  }[tone];
  return (
    <div role={tone === "error" ? "alert" : "status"} className={`flex gap-2.5 rounded-lg border px-3.5 py-3 text-sm ${config.styles}`}>
      <config.Icon aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  );
}

export function Loading({ label = "Loading" }: { label?: string }) {
  return (
    <p role="status" className="flex items-center justify-center gap-2 py-10 text-sm text-fg-subtle">
      <Loader2 aria-hidden="true" className="size-4 animate-spin" />
      {label}…
    </p>
  );
}

export function Empty({ children, icon: Icon = Inbox }: { children: ReactNode; icon?: LucideIcon }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-line px-4 py-10 text-center">
      <span className="flex size-10 items-center justify-center rounded-full bg-subtle text-fg-subtle">
        <Icon aria-hidden="true" className="size-5" />
      </span>
      <p className="text-sm text-fg-muted">{children}</p>
    </div>
  );
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
    <div role="alert" className="flex gap-2.5 rounded-lg border border-danger-line bg-danger-soft px-4 py-3 text-sm text-danger-text">
      <AlertCircle aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <div>
        <p>{message}</p>
        {error.traceId && <p className="mt-1 font-mono text-xs opacity-80">Reference: {error.traceId}</p>}
        {onRetry && !error.isForbidden && !error.isNotFound && (
          <Button variant="secondary" size="sm" className="mt-3" onClick={onRetry}>
            Try again
          </Button>
        )}
      </div>
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
