import Link from "next/link";
import { PlaceholderNote, Pill, ProgressBar, SectionCard, type Tone } from "@/components/card";
import {
  demoAccounts,
  demoBrainSummary,
  demoCurrentStep,
  demoHealth,
  demoProgress,
  demoRecentOutputs,
  demoRecommendation,
  demoRoadmap,
  demoProject,
  demoUsage,
} from "@/lib/mock-workspace";
import { projectHref } from "@/lib/navigation";
import type { OutputAnalysisStatus } from "@vibecode/contracts";

/**
 * The project workspace overview.
 *
 * Every panel reads from `lib/mock-workspace`. When a module's API lands, its panel switches to
 * `lib/api` and nothing else on this page moves.
 */
export default async function ProjectPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const percent = Math.round((demoProgress.completedTasks / demoProgress.totalTasks) * 100);

  return (
    <>
      <ProjectHeader id={id} />

      <section className="mt-8 grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <SectionCard title="Project progress">
          <div className="flex items-baseline justify-between">
            <span className="text-2xl font-bold text-white">{percent}%</span>
            <span className="text-ink-muted">
              {demoProgress.completedTasks} of {demoProgress.totalTasks} steps
            </span>
          </div>
          <div className="mt-3">
            <ProgressBar value={percent} label="Project progress" />
          </div>
          <p className="mt-3 text-ink-muted">Phase: {demoProgress.currentPhase}</p>
        </SectionCard>

        <SectionCard
          title="Project health"
          action={<Pill tone={demoHealth.tone as Tone}>{demoHealth.label}</Pill>}
        >
          <p className="text-ink-muted">{demoHealth.detail}</p>
          <PlaceholderNote>Guardians are contracts only in this phase.</PlaceholderNote>
        </SectionCard>

        <SectionCard title="Current step" action={<Pill tone="accent">{demoCurrentStep.status}</Pill>}>
          <p className="font-semibold text-white">{demoCurrentStep.title}</p>
          <p className="mt-1 text-ink-muted">{demoCurrentStep.objective}</p>
          <ul className="mt-3 space-y-1 text-ink-muted">
            {demoCurrentStep.completionCriteria.map((criterion) => (
              <li key={criterion} className="flex gap-2">
                <span className="text-accent-soft">·</span>
                {criterion}
              </li>
            ))}
          </ul>
        </SectionCard>

        <SectionCard
          title="Project Brain"
          action={
            <Link href={projectHref(id, "brain")} className="font-mono text-[11px] text-accent-soft">
              open
            </Link>
          }
        >
          <ul className="space-y-1.5">
            {demoBrainSummary.map((item) => (
              <li key={item.type} className="flex items-center justify-between">
                <span className="font-mono text-xs text-ink-muted">{item.type}</span>
                <span className="text-white">{item.count}</span>
              </li>
            ))}
          </ul>
        </SectionCard>

        <SectionCard
          title="Roadmap"
          action={
            <Link
              href={projectHref(id, "roadmap")}
              className="font-mono text-[11px] text-accent-soft"
            >
              open
            </Link>
          }
        >
          <ul className="space-y-3">
            {demoRoadmap.map((phase) => (
              <li key={phase.name}>
                <div className="flex items-center justify-between text-ink-muted">
                  <span>{phase.name}</span>
                  <span className="font-mono text-xs">
                    {phase.done}/{phase.total}
                  </span>
                </div>
                <div className="mt-1.5">
                  <ProgressBar
                    value={(phase.done / phase.total) * 100}
                    label={`${phase.name} progress`}
                  />
                </div>
              </li>
            ))}
          </ul>
        </SectionCard>

        <SectionCard
          title="Recent outputs"
          action={
            <Link
              href={projectHref(id, "outputs")}
              className="font-mono text-[11px] text-accent-soft"
            >
              open
            </Link>
          }
        >
          <ul className="space-y-2.5">
            {demoRecentOutputs.map((output) => (
              <li key={output.label} className="flex items-center justify-between gap-3">
                <div className="min-w-0">
                  <p className="truncate text-ink">{output.label}</p>
                  <p className="font-mono text-[11px] text-ink-faint">{output.receivedAt}</p>
                </div>
                <Pill tone={statusTone(output.status)}>{output.status}</Pill>
              </li>
            ))}
          </ul>
        </SectionCard>

        <SectionCard title="Next recommendation" emphasis>
          <p className="font-semibold text-white">{demoRecommendation.action}</p>
          <p className="mt-2 text-ink-muted">{demoRecommendation.rationale}</p>
          <p className="mt-3 font-mono text-[11px] text-ink-faint">
            based on: {demoRecommendation.basedOn.join(" · ")}
          </p>
        </SectionCard>

        <SectionCard title="AI accounts">
          <ul className="space-y-2">
            {demoAccounts.map((account) => (
              <li key={account.provider} className="flex items-center justify-between">
                <span className="text-ink">{account.provider}</span>
                <Pill tone={account.connected ? "ok" : "idle"}>{account.label}</Pill>
              </li>
            ))}
          </ul>
          <PlaceholderNote>No provider is connected, so no balance is shown.</PlaceholderNote>
        </SectionCard>

        <SectionCard title="Usage">
          <div className="flex items-baseline justify-between">
            <span className="text-2xl font-bold text-white">{demoUsage.tokensObserved}</span>
            <span className="text-ink-muted">tokens observed</span>
          </div>
          <p className="mt-3 text-ink-muted">
            Estimated spend: {demoUsage.estimatedSpend ?? "unknown"}
          </p>
          <PlaceholderNote>
            Confidence: {demoUsage.confidence} — an estimate is never shown as a balance.
          </PlaceholderNote>
        </SectionCard>
      </section>
    </>
  );
}

function ProjectHeader({ id }: { id: string }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div>
        <p className="eyebrow">project / {id}</p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">{demoProject.name}</h1>
        <p className="mt-2 max-w-2xl text-ink-muted">{demoProject.description}</p>
      </div>
      <Pill tone="ok">{demoProject.status}</Pill>
    </div>
  );
}

function statusTone(status: OutputAnalysisStatus): Tone {
  switch (status) {
    case "SUCCESS":
      return "ok";
    case "PARTIAL":
    case "NEEDS_VALIDATION":
      return "warn";
    case "FAILURE":
    case "BLOCKED":
      return "bad";
    default:
      return "idle";
  }
}
