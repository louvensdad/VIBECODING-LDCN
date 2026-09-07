import Link from "next/link";
import { Pill } from "@/components/card";
import { demoProjects } from "@/lib/mock-workspace";

export default function ProjectsPage() {
  return (
    <>
      <p className="eyebrow">projects</p>
      <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">Your workspaces</h1>
      <p className="mt-2 text-ink-muted">
        Each workspace keeps its own memory, roadmap and evidence trail.
      </p>

      <ul className="mt-8 grid gap-4 md:grid-cols-2">
        {demoProjects.map((project) => (
          <li key={project.id}>
            <Link href={`/projects/${project.id}`} className="card-interactive block p-6">
              <div className="flex items-start justify-between gap-3">
                <p className="font-semibold text-white">{project.name}</p>
                <Pill tone="ok">{project.status}</Pill>
              </div>
              <p className="mt-2 text-sm leading-6 text-ink-muted">{project.description}</p>
              <p className="mt-4 font-mono text-[11px] text-ink-faint">
                phase: {project.currentPhase}
              </p>
            </Link>
          </li>
        ))}
      </ul>
    </>
  );
}
