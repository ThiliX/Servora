import type { ReactNode } from "react";

/** Centered card used by the sign-in, registration and link-handling pages. */
export function AuthShell({ title, children }: { title: string; children: ReactNode }) {
  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-4 py-10">
      <p className="mb-6 text-center text-2xl font-bold text-indigo-700">Servora</p>
      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <h1 className="mb-4 text-lg font-semibold">{title}</h1>
        {children}
      </div>
    </main>
  );
}

/** Reads a token from the URL fragment (`#token=...`). Fragments are never sent to the server. */
export function tokenFromHash(hash: string): string | null {
  const token = new URLSearchParams(hash.replace(/^#/, "")).get("token");
  return token && token.length > 0 ? token : null;
}
