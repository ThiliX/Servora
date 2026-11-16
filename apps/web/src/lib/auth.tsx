import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import * as client from "./api";
import type { User } from "./types";

type AuthState = { status: "loading" } | { status: "anonymous" } | { status: "authenticated"; user: User };

interface AuthContextValue {
  state: AuthState;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;
    client.setSessionLostHandler(() => setState({ status: "anonymous" }));
    // On load, the HttpOnly refresh cookie (if any) restores the session.
    client
      .refreshSession()
      .then((user) => {
        if (!cancelled) {
          setState(user ? { status: "authenticated", user } : { status: "anonymous" });
        }
      })
      .catch(() => {
        if (!cancelled) {
          setState({ status: "anonymous" });
        }
      });
    return () => {
      cancelled = true;
      client.setSessionLostHandler(null);
    };
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const user = await client.login(email, password);
    setState({ status: "authenticated", user });
  }, []);

  const logout = useCallback(async () => {
    try {
      await client.logout();
    } finally {
      setState({ status: "anonymous" });
    }
  }, []);

  const value = useMemo(() => ({ state, login, logout }), [state, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return value;
}
