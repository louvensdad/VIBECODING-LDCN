import Link from "next/link";
import { Pill } from "@/components/card";
import { ApiOffline } from "@/components/workflow";
import { api, tryLoad } from "@/lib/api";

export default async function ProjectsPage() {
  const loaded = await tryLoad(() => api.listProjects());

  if (!loaded.ok) {
    return loaded.unreachable ? (
      <ApiOffline message={loaded.message} />
    ) : (
      <p className="text-ink-muted">{loaded.message}</p>
    );
  }

  const projects = loaded.data;

  return (
    <>
      <p className="eyebrow">projects</p>
      <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">Seus workspaces</h1>
      <p className="mt-2 text-ink-muted">
        Cada workspace mantém a própria memória, roadmap e trilha de evidências.
      </p>

      {projects.length === 0 ? (
        <div className="card mt-8 max-w-2xl p-6">
          <p className="font-semibold text-white">Nenhum projeto ainda</p>
          <p className="mt-2 text-sm leading-6 text-ink-muted">
            Crie o primeiro projeto pela API. Ele passa a ser o dono do próprio contexto a partir
            desse momento.
          </p>
          <pre className="mt-4 overflow-x-auto rounded-lg bg-surface-sunken p-4 font-mono text-xs text-ink-muted">
            {`curl -X POST http://localhost:8080/api/projects \\
  -H 'Content-Type: application/json' \\
  -d '{"name":"BarberFlow","originalIdea":"Agendamento para barbearias"}'`}
          </pre>
        </div>
      ) : (
        <ul className="mt-8 grid gap-4 md:grid-cols-2">
          {projects.map((project) => (
            <li key={project.id}>
              <Link href={`/projects/${project.id}`} className="card-interactive block p-6">
                <div className="flex items-start justify-between gap-3">
                  <p className="font-semibold text-white">{project.name}</p>
                  <Pill tone={project.status === "ACTIVE" ? "ok" : "idle"}>{project.status}</Pill>
                </div>
                <p className="mt-2 line-clamp-2 text-sm leading-6 text-ink-muted">
                  {project.description ?? project.originalIdea}
                </p>
                <p className="mt-4 font-mono text-[11px] text-ink-faint">
                  fase: {project.currentPhase ?? "—"}
                </p>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
