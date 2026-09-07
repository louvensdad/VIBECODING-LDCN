"use client";

import { useRouter } from "next/navigation";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { api, setSessionExpiredHandler } from "@/lib/api-client";
import type { AuthenticatedUser } from "@/lib/endpoints";

/**
 * Who is signed in, according to the backend.
 *
 * The state starts as `loading` and is resolved by asking `/api/auth/me` — never by looking for a
 * cookie or a flag in storage. A cookie's presence proves nothing about whether the session behind
 * it is still valid, and the server is the only thing that knows.
 */
export type SessionState =
  | { status: "loading" }
  | { status: "authenticated"; user: AuthenticatedUser }
  | { status: "unauthenticated" };

interface SessionContextValue {
  session: SessionState;
  refresh: () => Promise<void>;
  signOut: () => Promise<void>;
  /** Records a user just returned by login or register, avoiding a second round trip. */
  adopt: (user: AuthenticatedUser) => void;
}

const SessionContext = createContext<SessionContextValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<SessionState>({ status: "loading" });
  const router = useRouter();

  const refresh = useCallback(async () => {
    try {
      setSession({ status: "authenticated", user: await api.me() });
    } catch {
      // Any failure here means "not signed in as far as this app is concerned".
      setSession({ status: "unauthenticated" });
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // A 401 from anywhere clears the local state exactly once and sends the user to the login page.
  // Routing through this single handler is what keeps an expired session from turning into a
  // redirect loop.
  useEffect(() => {
    setSessionExpiredHandler(() => {
      setSession((current) =>
        current.status === "unauthenticated" ? current : { status: "unauthenticated" },
      );
    });
    return () => setSessionExpiredHandler(() => {});
  }, []);

  const signOut = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      setSession({ status: "unauthenticated" });
      router.push("/login");
      router.refresh();
    }
  }, [router]);

  const adopt = useCallback((user: AuthenticatedUser) => {
    setSession({ status: "authenticated", user });
  }, []);

  const value = useMemo(
    () => ({ session, refresh, signOut, adopt }),
    [session, refresh, signOut, adopt],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const context = useContext(SessionContext);
  if (!context) {
    throw new Error("useSession must be used inside a SessionProvider");
  }
  return context;
}

/**
 * Hides workspace UI until the session is known, and sends anonymous visitors to the login page.
 *
 * This is convenience, not security: every one of these pages is also enforced by the backend, and
 * removing this guard would change nothing about what data a user can actually obtain.
 */
export function RequireSession({ children }: { children: ReactNode }) {
  const { session } = useSession();
  const router = useRouter();

  useEffect(() => {
    if (session.status === "unauthenticated") {
      router.replace("/login");
    }
  }, [session.status, router]);

  if (session.status === "loading") {
    return <p className="p-6 font-mono text-xs text-ink-faint">carregando sessão…</p>;
  }
  if (session.status === "unauthenticated") {
    return <p className="p-6 font-mono text-xs text-ink-faint">redirecionando para o login…</p>;
  }
  return <>{children}</>;
}
