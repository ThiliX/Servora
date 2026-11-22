import type { ReactNode } from "react";
import { BookOpenCheck, ShieldCheck, UserRoundCheck } from "lucide-react";
import { Logo } from "./WorkspaceLayout";

const POINTS = [
  { icon: BookOpenCheck, title: "Answers from your documents", text: "Every reply cites the passage it came from." },
  { icon: UserRoundCheck, title: "A person when it matters", text: "Hand-offs to your team, with full context." },
  { icon: ShieldCheck, title: "Your data stays yours", text: "Each business is isolated, down to the database." },
];

/** The sign-in, registration and link-handling pages: a brand panel and the form. */
export function AuthShell({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="grid min-h-screen lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      <aside className="relative hidden overflow-hidden bg-zinc-950 p-12 text-white lg:flex lg:flex-col lg:justify-between">
        <div
          aria-hidden="true"
          className="absolute inset-0 bg-[radial-gradient(60%_50%_at_20%_10%,rgba(99,102,241,0.35),transparent),radial-gradient(50%_40%_at_90%_90%,rgba(139,92,246,0.25),transparent)]"
        />
        <div className="relative flex items-center gap-2.5">
          <Logo size={32} />
          <span className="text-lg font-semibold tracking-tight">Servora</span>
        </div>
        <div className="relative max-w-md space-y-8">
          <p className="text-3xl leading-tight font-semibold tracking-tight">AI customer support your customers can trust.</p>
          <ul className="space-y-5">
            {POINTS.map((point) => (
              <li key={point.title} className="flex gap-3">
                <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-white/10 ring-1 ring-white/15">
                  <point.icon aria-hidden="true" className="size-4.5" />
                </span>
                <span>
                  <span className="block text-sm font-medium">{point.title}</span>
                  <span className="block text-sm text-zinc-300">{point.text}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-xs text-zinc-400">Multi-tenant AI support platform</p>
      </aside>
      <main className="flex flex-col items-center justify-center px-4 py-12 sm:px-8">
        <div className="w-full max-w-sm">
          <div className="mb-8 flex items-center gap-2.5 lg:hidden">
            <Logo />
            <span className="text-lg font-semibold tracking-tight">Servora</span>
          </div>
          <h1 className="mb-6 text-2xl font-semibold tracking-tight text-fg">{title}</h1>
          {children}
        </div>
      </main>
    </div>
  );
}

/** Reads a token from the URL fragment (`#token=...`). Fragments are never sent to the server. */
export function tokenFromHash(hash: string): string | null {
  const token = new URLSearchParams(hash.replace(/^#/, "")).get("token");
  return token && token.length > 0 ? token : null;
}
