import Link from "next/link";
import { PlaceholderNote } from "@/components/card";

interface SettingsGroup {
  title: string;
  body: string;
  /** Present when the section is real and lives somewhere. */
  href?: string;
}

const SETTINGS_GROUPS: SettingsGroup[] = [
  {
    title: "Model providers",
    body: "Connections live in AI Connections. Each credential belongs to the user who added it, is encrypted before storage, and cannot be read back by anything — including this page.",
    href: "/connections",
  },
  {
    title: "Budgets",
    body: "Set a spending ceiling per project. Exact balances and estimates are always labelled separately.",
  },
  {
    title: "Wellness",
    body: "Breaks, focus blocks and reminders. Off by default.",
  },
];

export default function SettingsPage() {
  return (
    <>
      <p className="eyebrow">workspace</p>
      <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">Settings</h1>
      <p className="mt-2 max-w-2xl text-ink-muted">
        Most of this is not configurable yet. Sections without a link show what the page will hold.
      </p>

      <div className="mt-8 grid gap-4 md:grid-cols-3">
        {SETTINGS_GROUPS.map((group) => (
          <article key={group.title} className="card p-6">
            <h2 className="font-semibold text-white">{group.title}</h2>
            <p className="mt-2 text-sm leading-6 text-ink-muted">{group.body}</p>
            {group.href ? (
              <Link
                href={group.href}
                className="mt-3 inline-block text-sm font-semibold text-accent-soft"
              >
                Abrir →
              </Link>
            ) : (
              <PlaceholderNote>Not implemented in this phase.</PlaceholderNote>
            )}
          </article>
        ))}
      </div>
    </>
  );
}
