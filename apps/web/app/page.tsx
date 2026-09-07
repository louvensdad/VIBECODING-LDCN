import Link from "next/link";

const PILLARS = [
  {
    title: "Memory that outlives the model",
    body: "Vision, decisions and rules live in the Project Brain — not in a chat window you will lose.",
  },
  {
    title: "Evidence before progress",
    body: "Output is analyzed for real signals. A claim of success never advances the roadmap.",
  },
  {
    title: "Providers stay replaceable",
    body: "Switch between models without losing what the project knows about itself.",
  },
];

export default function Home() {
  return (
    <>
      <section className="py-10 md:py-16">
        <p className="eyebrow">project guidance system</p>
        <h1 className="mt-5 max-w-4xl text-4xl font-extrabold leading-[1.1] tracking-tight text-white md:text-6xl">
          Your project remembers the decisions that got it here.
        </h1>
        <p className="mt-6 max-w-2xl text-lg leading-8 text-ink-muted">
          VibeCode turns scattered LLM work into durable project context, clear next steps and
          progress you can actually verify.
        </p>
        <div className="mt-9 flex flex-wrap gap-3">
          <Link
            href="/projects"
            className="rounded-lg bg-accent px-5 py-3 font-semibold text-white transition-colors hover:bg-accent-soft"
          >
            Abrir workspace
          </Link>
          <Link
            href="/projects"
            className="rounded-lg border border-edge-strong px-5 py-3 font-semibold text-ink transition-colors hover:border-accent/60"
          >
            Ver projetos
          </Link>
        </div>
      </section>

      <section className="grid gap-4 pb-10 md:grid-cols-3">
        {PILLARS.map((pillar) => (
          <article key={pillar.title} className="card p-6">
            <h2 className="font-semibold text-white">{pillar.title}</h2>
            <p className="mt-2 text-sm leading-6 text-ink-muted">{pillar.body}</p>
          </article>
        ))}
      </section>
    </>
  );
}
