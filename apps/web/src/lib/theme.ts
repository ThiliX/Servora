import { useCallback, useEffect, useState } from "react";

export type ThemePreference = "light" | "dark" | "system";

const STORAGE_KEY = "servora:theme";

function readPreference(): ThemePreference {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return value === "light" || value === "dark" ? value : "system";
  } catch {
    return "system";
  }
}

function systemPrefersDark(): boolean {
  return typeof window.matchMedia === "function" && window.matchMedia("(prefers-color-scheme: dark)").matches;
}

/** Applies a preference to the page: the `dark` class on <html> drives every token. */
export function applyTheme(preference: ThemePreference): void {
  const dark = preference === "dark" || (preference === "system" && systemPrefersDark());
  document.documentElement.classList.toggle("dark", dark);
}

/** The saved theme preference, with a setter that saves it (when storage allows) and applies it. */
export function useTheme(): { preference: ThemePreference; setPreference: (next: ThemePreference) => void } {
  const [preference, setState] = useState<ThemePreference>(readPreference);

  useEffect(() => {
    applyTheme(preference);
    if (preference !== "system" || typeof window.matchMedia !== "function") {
      return;
    }
    // Follow the system while the preference is "system".
    const query = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => applyTheme("system");
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, [preference]);

  const setPreference = useCallback((next: ThemePreference) => {
    try {
      if (next === "system") {
        window.localStorage.removeItem(STORAGE_KEY);
      } else {
        window.localStorage.setItem(STORAGE_KEY, next);
      }
    } catch {
      // Storage blocked: the choice applies to this visit only.
    }
    setState(next);
  }, []);

  return { preference, setPreference };
}
