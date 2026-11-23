import { useEffect, useState } from "react";

/**
 * The public demo's configuration, written by scripts/live-demo.mjs on each reset and served next
 * to the dashboard. Normal deployments have no such file, and the dashboard behaves as usual.
 */
export interface LiveDemoAccount {
  label: string;
  email: string;
  password: string;
}

export interface LiveDemoSite {
  label: string;
  url: string;
}

export interface LiveDemoConfig {
  accounts: LiveDemoAccount[];
  sites: LiveDemoSite[];
  reset: string;
}

export const LIVE_DEMO_CONFIG_URL = "/live-demo/config.json";

function isText(value: unknown): value is string {
  return typeof value === "string" && value.length > 0 && value.length < 500;
}

function isHttpUrl(value: unknown): value is string {
  if (!isText(value)) return false;
  try {
    const protocol = new URL(value).protocol;
    return protocol === "https:" || protocol === "http:";
  } catch {
    return false;
  }
}

/** Accepts only the expected shape; anything else (including the app's own HTML page) means "no demo". */
export function parseLiveDemoConfig(value: unknown): LiveDemoConfig | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const { accounts, sites, reset } = value as Record<string, unknown>;
  if (!Array.isArray(accounts) || !Array.isArray(sites) || !isText(reset)) return null;
  const validAccounts = accounts.filter(
    (a): a is LiveDemoAccount =>
      typeof a === "object" && a !== null && isText(a.label) && isText(a.email) && isText(a.password),
  );
  const validSites = sites.filter(
    (s): s is LiveDemoSite => typeof s === "object" && s !== null && isText(s.label) && isHttpUrl(s.url),
  );
  if (validAccounts.length === 0) return null;
  return {
    accounts: validAccounts.map(({ label, email, password }) => ({ label, email, password })),
    sites: validSites.map(({ label, url }) => ({ label, url })),
    reset,
  };
}

let cached: Promise<LiveDemoConfig | null> | null = null;

async function load(): Promise<LiveDemoConfig | null> {
  try {
    const response = await fetch(LIVE_DEMO_CONFIG_URL, { headers: { Accept: "application/json" } });
    if (!response.ok || !(response.headers.get("Content-Type") ?? "").includes("json")) return null;
    return parseLiveDemoConfig(await response.json());
  } catch {
    return null;
  }
}

/** Loads the demo configuration once per page load. */
export function loadLiveDemo(): Promise<LiveDemoConfig | null> {
  cached ??= load();
  return cached;
}

/** For tests. */
export function resetLiveDemoCache() {
  cached = null;
}

export function useLiveDemo(): LiveDemoConfig | null {
  const [config, setConfig] = useState<LiveDemoConfig | null>(null);
  useEffect(() => {
    let cancelled = false;
    void loadLiveDemo().then((loaded) => {
      if (!cancelled) setConfig(loaded);
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return config;
}
