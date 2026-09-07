import { Pill } from "@/components/card";
import { OutputPanel } from "@/components/output-panel";
import { LoadFailure, analysisTone } from "@/components/workflow";
import { serverApi as api, tryLoad } from "@/lib/api-server";

/** Where output becomes evidence — or gets analysed without being stored. */
export default async function OutputsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  const loaded = await tryLoad(async () => {
    const [state, recent] = await Promise.all([api.getState(id), api.recentEvidence(id, 20)]);
    return { state, recent };
  });

  if (!loaded.ok) {
    return <LoadFailure reason={loaded.reason} message={loaded.message} />;
  }

  const { state, recent } = loaded.data;
  const tasks = [
    ...(state.currentTask ? [state.currentTask] : []),
    ...state.nextCandidateTasks.filter((task) => task.id !== state.currentTask?.id),
  ];

  return (
    <>
      <p className="eyebrow">outputs</p>
      <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">Saídas e evidências</h1>
      <p className="mt-2 max-w-2xl text-ink-muted">
        A análise é determinística: evidência técnica sempre vence uma alegação de conclusão.
      </p>

      <div className="mt-8 space-y-6">
        <OutputPanel projectId={id} tasks={tasks} defaultTaskId={state.currentTask?.id ?? null} />

        <section className="card p-6">
          <h2 className="text-lg font-semibold text-white">Histórico</h2>
          <p className="mt-1 text-sm text-ink-muted">
            Evidência é append-only: a falha continua registrada depois da correção.
          </p>

          {recent.length === 0 ? (
            <p className="mt-5 text-sm text-ink-faint">Nada registrado ainda.</p>
          ) : (
            <ul className="mt-5 divide-y divide-edge">
              {recent.map((item) => (
                <li key={item.id} className="flex items-start justify-between gap-4 py-3">
                  <div className="min-w-0">
                    <p className="text-sm text-ink">{item.summary ?? item.type}</p>
                    <p className="mt-0.5 font-mono text-[11px] text-ink-faint">
                      {item.type} · {item.source} ·{" "}
                      {new Date(item.createdAt).toLocaleString("pt-BR")}
                    </p>
                  </div>
                  <Pill tone={analysisTone(item.status)}>{item.status ?? "—"}</Pill>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </>
  );
}
