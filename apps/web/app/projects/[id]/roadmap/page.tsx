import { Pill } from "@/components/card";
import { LoadFailure, RoadmapTree, phaseTone, taskTone } from "@/components/workflow";
import { serverApi as api, tryLoad } from "@/lib/api-server";

/** The full plan, phase by phase, with each task's derived status. */
export default async function RoadmapPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  const loaded = await tryLoad(async () => {
    const [state, roadmap] = await Promise.all([
      api.getState(id),
      api.getRoadmap(id).catch(() => null),
    ]);
    return { state, roadmap };
  });

  if (!loaded.ok) {
    return <LoadFailure reason={loaded.reason} message={loaded.message} />;
  }

  const { state, roadmap } = loaded.data;
  const phases = roadmap?.phases ?? [];

  return (
    <>
      <p className="eyebrow">roadmap</p>
      <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">Plano do projeto</h1>
      <p className="mt-2 max-w-2xl text-ink-muted">
        Fases e tarefas são criadas manualmente. O status de cada fase é derivado das suas tarefas,
        nunca digitado.
      </p>

      <div className="mt-8 grid gap-6 lg:grid-cols-[20rem_1fr]">
        <section className="card h-fit p-6">
          <h2 className="label">visão geral</h2>
          <div className="mt-4">
            <RoadmapTree phases={phases} currentTaskId={state.currentTask?.id ?? null} />
          </div>
        </section>

        <div className="space-y-4">
          {phases.length === 0 ? (
            <div className="card p-6">
              <p className="font-semibold text-white">Nenhuma fase ainda</p>
              <p className="mt-2 text-sm leading-6 text-ink-muted">
                Crie o roadmap pela API e adicione fases e tarefas. Nenhum roadmap é gerado por IA
                nesta fase.
              </p>
              <pre className="mt-4 overflow-x-auto rounded-lg bg-surface-sunken p-4 font-mono text-xs text-ink-muted">
                {`POST /api/projects/${id}/roadmap\nPOST /api/projects/${id}/roadmap/phases`}
              </pre>
            </div>
          ) : (
            phases.map((phase) => (
              <section key={phase.id} className="card p-6">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <h2 className="text-lg font-semibold uppercase tracking-wide text-white">
                    {phase.title}
                  </h2>
                  <div className="flex items-center gap-3">
                    <span className="font-mono text-[11px] text-ink-faint">
                      {phase.completedTasks}/{phase.totalTasks}
                    </span>
                    <Pill tone={phaseTone(phase.status)}>{phase.status}</Pill>
                  </div>
                </div>

                {phase.description ? (
                  <p className="mt-2 text-sm text-ink-muted">{phase.description}</p>
                ) : null}

                {phase.tasks.length === 0 ? (
                  <p className="mt-4 text-sm text-ink-faint">Nenhuma tarefa nesta fase.</p>
                ) : (
                  <ul className="mt-4 divide-y divide-edge">
                    {phase.tasks.map((task) => (
                      <li key={task.id} className="flex items-center justify-between gap-3 py-2.5">
                        <span
                          className={
                            task.id === state.currentTask?.id
                              ? "font-semibold text-white"
                              : "text-ink"
                          }
                        >
                          {task.position}. {task.title}
                        </span>
                        <Pill tone={taskTone(task.status)}>{task.status}</Pill>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            ))
          )}
        </div>
      </div>
    </>
  );
}
