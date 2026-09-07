import Link from "next/link";
import { Pill, ProgressBar, SectionCard } from "@/components/card";
import { CriticalSecurityBanner, gateTone } from "@/components/security";
import { LoadFailure, NextStepCard, RoadmapTree, analysisTone, taskTone } from "@/components/workflow";
import { serverApi as api, tryLoad } from "@/lib/api-server";
import { projectHref } from "@/lib/navigation";

/**
 * The project workspace: where you are, what is done, what is in the way, and what to do next.
 *
 * Everything on this page comes from the API. There is no mocked workspace data any more.
 */
export default async function ProjectPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  const loaded = await tryLoad(async () => {
    const [project, state, guide, recentEvidence, brain] = await Promise.all([
      api.getProject(id),
      api.getState(id),
      api.getGuide(id),
      api.recentEvidence(id, 5),
      api.getBrain(id),
    ]);
    // A project without a roadmap is a normal starting state, not an error.
    const roadmap = await api.getRoadmap(id).catch(() => null);
    const security = await api.getSecurity(id).catch(() => null);
    return { project, state, guide, recentEvidence, brain, roadmap, security };
  });

  if (!loaded.ok) {
    return <LoadFailure reason={loaded.reason} message={loaded.message} />;
  }

  const { project, state, guide, recentEvidence, brain, roadmap, security } = loaded.data;

  return (
    <>
      <CriticalSecurityBanner assessment={security} href={projectHref(id, "security")} />

      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="eyebrow">project</p>
          <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">{project.name}</h1>
          <p className="mt-2 max-w-2xl text-ink-muted">
            {project.description ?? project.originalIdea}
          </p>
        </div>
        <Pill tone="ok">{project.status}</Pill>
      </header>

      <div className="mt-6 flex flex-wrap gap-2">
        <ActionLink href={projectHref(id, "roadmap")}>Ver roadmap</ActionLink>
        <ActionLink href={projectHref(id, "outputs")}>Registrar saída</ActionLink>
        <ActionLink href={projectHref(id, "prompts")}>Gerar próximo prompt</ActionLink>
        <ActionLink href={projectHref(id, "security")}>Ver segurança</ActionLink>
      </div>

      <section className="mt-8 grid gap-4 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <NextStepCard
            nextStep={guide.recommendedNextStep}
            action={
              <Link
                href={projectHref(id, "prompts")}
                className="inline-block rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-accent-soft"
              >
                Gerar prompt de {guide.recommendedNextStep.suggestedPromptType}
              </Link>
            }
          />
        </div>

        <SectionCard title="Progresso">
          <div className="flex items-baseline justify-between">
            <span className="text-2xl font-bold text-white">{state.progressPercentage}%</span>
            <span className="text-ink-muted">
              {state.completedTasks} de {state.totalTasks} tarefas
            </span>
          </div>
          <div className="mt-3">
            <ProgressBar value={state.progressPercentage} label="Progresso do projeto" />
          </div>
          <p className="mt-3 text-ink-muted">{guide.whereYouAre}</p>
          {state.blockedTasks > 0 ? (
            <p className="mt-2 text-signal-bad">{state.blockedTasks} tarefa(s) bloqueada(s)</p>
          ) : null}
        </SectionCard>

        <SectionCard title="Fase atual">
          {state.currentPhase ? (
            <>
              <p className="font-semibold text-white">{state.currentPhase.title}</p>
              <div className="mt-2">
                <Pill tone={analysisTone(null)}>{state.currentPhase.status}</Pill>
              </div>
            </>
          ) : (
            <p className="text-ink-muted">Nenhuma fase em aberto.</p>
          )}
        </SectionCard>

        <SectionCard title="Tarefa atual">
          {state.currentTask ? (
            <>
              <p className="font-semibold text-white">{state.currentTask.title}</p>
              <div className="mt-2">
                <Pill tone={taskTone(state.currentTask.status)}>{state.currentTask.status}</Pill>
              </div>
            </>
          ) : (
            <p className="text-ink-muted">Nenhuma tarefa em aberto.</p>
          )}
        </SectionCard>

        <SectionCard
          title="Roadmap"
          action={
            <Link href={projectHref(id, "roadmap")} className="font-mono text-[11px] text-accent-soft">
              abrir
            </Link>
          }
        >
          <RoadmapTree
            phases={roadmap?.phases ?? []}
            currentTaskId={state.currentTask?.id ?? null}
          />
        </SectionCard>

        <SectionCard
          title="Evidências recentes"
          action={
            <Link href={projectHref(id, "outputs")} className="font-mono text-[11px] text-accent-soft">
              registrar
            </Link>
          }
        >
          {recentEvidence.length === 0 ? (
            <p className="text-ink-muted">Nenhuma evidência registrada ainda.</p>
          ) : (
            <ul className="space-y-2.5">
              {recentEvidence.map((item) => (
                <li key={item.id} className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="truncate text-ink">{item.type}</p>
                    <p className="font-mono text-[11px] text-ink-faint">{item.source}</p>
                  </div>
                  <Pill tone={analysisTone(item.status)}>{item.status ?? "—"}</Pill>
                </li>
              ))}
            </ul>
          )}
        </SectionCard>

        <SectionCard
          title="Segurança"
          action={
            <Link
              href={projectHref(id, "security")}
              className="font-mono text-[11px] text-accent-soft"
            >
              abrir
            </Link>
          }
        >
          {security ? (
            <>
              <div className="flex items-baseline justify-between">
                <span className="text-2xl font-bold text-white">{security.score}</span>
                <Pill tone={gateTone(security.gateStatus)}>{security.gateStatus}</Pill>
              </div>
              <p className="mt-3 text-ink-muted">
                {security.openFindings === 0
                  ? "Nenhum problema em aberto."
                  : `${security.openFindings} em aberto · ${security.critical} crítico(s)`}
              </p>
            </>
          ) : (
            <p className="text-ink-muted">Sem avaliação de segurança ainda.</p>
          )}
        </SectionCard>

        <SectionCard
          title="Project Brain"
          action={
            <Link href={projectHref(id, "brain")} className="font-mono text-[11px] text-accent-soft">
              abrir
            </Link>
          }
        >
          {brain.entryCount === 0 ? (
            <p className="text-ink-muted">
              Memória vazia. Eventos geram propostas — nada entra sem revisão.
            </p>
          ) : (
            <ul className="space-y-1.5">
              {Object.entries(brain.countByType).map(([type, count]) => (
                <li key={type} className="flex items-center justify-between">
                  <span className="font-mono text-xs text-ink-muted">{type}</span>
                  <span className="text-white">{count}</span>
                </li>
              ))}
            </ul>
          )}
        </SectionCard>

        {state.activeProblems.length > 0 ? (
          <div className="lg:col-span-3">
            <SectionCard title="Problemas ativos">
              <ul className="space-y-1 text-signal-warn">
                {state.activeProblems.map((problem) => (
                  <li key={problem}>· {problem}</li>
                ))}
              </ul>
            </SectionCard>
          </div>
        ) : null}
      </section>
    </>
  );
}

function ActionLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      className="rounded-lg border border-edge-strong px-3.5 py-2 text-sm text-ink transition-colors hover:border-accent/60 hover:text-white"
    >
      {children}
    </Link>
  );
}
