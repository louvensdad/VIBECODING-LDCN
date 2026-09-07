import { PlaceholderNote } from "@/components/card";

const SETTINGS_GROUPS = [
  {
    title: "Model providers",
    body: "Connect accounts and choose default models. Credentials are read from the environment and never stored in the database.",
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
        Nothing here is configurable yet. The sections show what this page will hold.
      </p>

      <div className="mt-8 grid gap-4 md:grid-cols-3">
        {SETTINGS_GROUPS.map((group) => (
          <article key={group.title} className="card p-6">
            <h2 className="font-semibold text-white">{group.title}</h2>
            <p className="mt-2 text-sm leading-6 text-ink-muted">{group.body}</p>
            <PlaceholderNote>Not implemented in this phase.</PlaceholderNote>
          </article>
        ))}
      </div>
    </>
  );
}
