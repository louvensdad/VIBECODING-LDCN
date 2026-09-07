"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { projectHref, projectNav } from "@/lib/navigation";

/**
 * The workspace frame: a fixed sidebar of project modules and a topbar showing where you are.
 *
 * The active project is read from the URL. Until projects are selectable, any route under
 * /projects/[id] keeps the sidebar pointed at that id.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname() ?? "/";
  const projectId = pathname.match(/^\/projects\/([^/]+)/)?.[1] ?? "demo";

  return (
    <div className="shell md:flex">
      <Sidebar projectId={projectId} pathname={pathname} />
      <div className="min-w-0 flex-1 md:ml-64">
        <Topbar pathname={pathname} />
        <main className="mx-auto max-w-7xl p-6 md:p-9">{children}</main>
      </div>
    </div>
  );
}

function Sidebar({ projectId, pathname }: { projectId: string; pathname: string }) {
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

      <p className="label mt-8">Workspace</p>
      <nav className="mt-3 grid grid-cols-2 gap-1 md:grid-cols-1">
        {[
          { label: "All projects", href: "/projects" },
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
      <span className="rounded-lg border border-edge-strong px-3 py-1.5 font-mono text-[11px] text-ink-muted">
        foundation
      </span>
    </header>
  );
}
