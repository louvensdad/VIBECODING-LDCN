"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState, type ReactNode } from "react";
import { projectHref, projectNav } from "@/lib/navigation";
import { RequireSession, useSession } from "./session";

const AUTH_ROUTES = ["/login", "/register"];

/**
 * The workspace frame: a fixed sidebar of project modules and a topbar with the account menu.
 *
 * The login and register pages render bare — a sidebar full of project links would be misleading
 * to someone who is not signed in.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname() ?? "/";
  const { session } = useSession();

  if (AUTH_ROUTES.some((route) => pathname.startsWith(route))) {
    return (
      <div className="shell">
        <main className="mx-auto max-w-7xl p-6 md:p-9">{children}</main>
      </div>
    );
  }

  const projectId = pathname.match(/^\/projects\/([^/]+)/)?.[1] ?? null;
  const signedIn = session.status === "authenticated";
  const protectedWorkspace =
    pathname === "/settings" ||
    pathname.startsWith("/connections") ||
    pathname.startsWith("/projects");

  return (
    <div className="shell md:flex">
      <Sidebar projectId={projectId} pathname={pathname} signedIn={signedIn} />
      <div className="min-w-0 flex-1 md:ml-64">
        <Topbar pathname={pathname} />
        <main className="mx-auto max-w-7xl p-6 md:p-9">
          {protectedWorkspace ? <RequireSession>{children}</RequireSession> : children}
        </main>
      </div>
    </div>
  );
}

function Sidebar({
  projectId,
  pathname,
  signedIn,
}: {
  projectId: string | null;
  pathname: string;
  signedIn: boolean;
}) {
  return (
    <aside className="border-b border-edge bg-surface-sunken/95 px-5 py-6 md:fixed md:inset-y-0 md:w-64 md:overflow-y-auto md:border-b-0 md:border-r">
      <Link href="/" className="flex items-center gap-3">
        <span className="grid h-9 w-9 place-items-center rounded-lg bg-accent font-black text-white">
          V
        </span>
        <span className="font-extrabold tracking-tight">
          VIBE<span className="text-accent-soft">CODE</span>
        </span>
      </Link>

      {signedIn && projectId ? (
        <>
          <p className="label mt-8">Project</p>
          <nav className="mt-3 grid grid-cols-2 gap-1 md:grid-cols-1">
            {projectNav.map((item) => {
              const href = projectHref(projectId, item.segment);
              const active = pathname === href;
              return (
                <Link
                  key={href}
                  href={href}
                  aria-current={active ? "page" : undefined}
                  className={`rounded-lg px-3 py-2 text-sm transition-colors ${
                    active
                      ? "bg-accent/15 text-white"
                      : "text-ink-muted hover:bg-surface-raised hover:text-ink"
                  }`}
                >
                  {item.label}
                </Link>
              );
            })}
          </nav>
        </>
      ) : null}

      {signedIn ? (
        <>
          <p className="label mt-8">Workspace</p>
          <nav className="mt-3 grid grid-cols-2 gap-1 md:grid-cols-1">
            {[
              { label: "All projects", href: "/projects" },
              { label: "AI Connections", href: "/connections" },
              { label: "Settings", href: "/settings" },
            ].map((item) => (
              <Link
                key={item.href}
                href={item.href}
                className={`rounded-lg px-3 py-2 text-sm transition-colors ${
                  pathname === item.href
                    ? "bg-accent/15 text-white"
                    : "text-ink-muted hover:bg-surface-raised hover:text-ink"
                }`}
              >
                {item.label}
              </Link>
            ))}
          </nav>
        </>
      ) : null}

      <p className="mt-8 border-t border-edge pt-5 text-xs leading-5 text-ink-faint">
        Project context is official here. No model owns this state.
      </p>
    </aside>
  );
}

function Topbar({ pathname }: { pathname: string }) {
  const crumbs = pathname.split("/").filter(Boolean);
  return (
    <header className="flex h-16 items-center justify-between gap-4 border-b border-edge bg-surface-sunken/70 px-6 backdrop-blur">
      <span className="truncate font-mono text-xs uppercase tracking-[0.14em] text-ink-faint">
        {crumbs.length ? crumbs.join(" / ") : "workspace"}
      </span>
      <AccountMenu />
    </header>
  );
}

/** Avatar, name and sign-out. */
function AccountMenu() {
  const { session, signOut } = useSession();
  const [open, setOpen] = useState(false);

  if (session.status === "loading") {
    return <span className="font-mono text-[11px] text-ink-faint">…</span>;
  }

  if (session.status === "unauthenticated") {
    return (
      <div className="flex items-center gap-2">
        <Link href="/login" className="rounded-lg px-3 py-1.5 text-sm text-ink-muted hover:text-ink">
          Entrar
        </Link>
        <Link
          href="/register"
          className="rounded-lg bg-accent px-3 py-1.5 text-sm font-semibold text-white"
        >
          Criar conta
        </Link>
      </div>
    );
  }

  const { user } = session;
  const initial = (user.displayName || user.email).charAt(0).toUpperCase();

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-haspopup="menu"
        className="flex items-center gap-2.5 rounded-lg px-2 py-1.5 transition-colors hover:bg-surface-raised"
      >
        <span className="grid h-7 w-7 place-items-center rounded-full bg-accent text-sm font-bold text-white">
          {initial}
        </span>
        <span className="hidden max-w-[12rem] truncate text-sm text-ink sm:block">
          {user.displayName}
        </span>
      </button>

      {open ? (
        <div
          role="menu"
          className="absolute right-0 z-20 mt-2 w-60 rounded-card border border-edge bg-surface-raised p-2 shadow-xl"
        >
          <div className="border-b border-edge px-3 pb-2.5 pt-1.5">
            <p className="truncate text-sm text-white">{user.displayName}</p>
            <p className="truncate font-mono text-[11px] text-ink-faint">{user.email}</p>
            <p className="mt-1 font-mono text-[10px] uppercase tracking-wider text-ink-faint">
              {user.role}
            </p>
          </div>
          <button
            type="button"
            onClick={() => {
              setOpen(false);
              void signOut();
            }}
            className="mt-1 w-full rounded-lg px-3 py-2 text-left text-sm text-ink-muted transition-colors hover:bg-surface hover:text-ink"
          >
            Sair
          </button>
        </div>
      ) : null}
    </div>
  );
}
