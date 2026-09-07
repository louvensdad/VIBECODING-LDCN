import type {
  NextStepResponse,
  OutputAnalysisStatus,
  PhaseResponse,
  PhaseStatus,
  TaskStatus,
} from "@vibecode/contracts";
import { Pill, type Tone } from "./card";

/** Status markers shared by the roadmap tree and the task lists. */
const PHASE_MARK: Record<PhaseStatus, string> = {
  COMPLETED: "âœ“",
  IN_PROGRESS: "â—",
  BLOCKED: "â–²",
  READY: "â—‹",
  PLANNED: "â—‹",
  SKIPPED: "â€“",
};

const TASK_MARK: Record<TaskStatus, string> = {
  COMPLETED: "âœ“",
  IN_PROGRESS: "â—",
  NEEDS_VALIDATION: "â—",
  BLOCKED: "â–²",
  READY: "â—‹",
  PLANNED: "â—‹",
  SKIPPED: "â€“",
};

export function phaseTone(status: PhaseStatus): Tone {
  switch (status) {
    case "COMPLETED":
      return "ok";
    case "IN_PROGRESS":
      return "accent";
    case "BLOCKED":
      return "bad";
    default:
      return "idle";
  }
}

export function taskTone(status: TaskStatus): Tone {
  switch (status) {
    case "COMPLETED":
      return "ok";
    case "IN_PROGRESS":
      return "accent";
    case "NEEDS_VALIDATION":
      return "warn";
    case "BLOCKED":
      return "bad";
    default:
      return "idle";
  }
}

export function analysisTone(status: OutputAnalysisStatus | null): Tone {
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

const MARK_COLOR: Record<Tone, string> = {
  ok: "text-signal-ok",
  accent: "text-accent-soft",
  warn: "text-signal-warn",
  bad: "text-signal-bad",
  idle: "text-ink-faint",
};

/**
 * The roadmap as a plain tree.
 *
 * Deliberately static: no animation, no drag. It exists to answer "where am I?" at a glance.
 */
export function RoadmapTree({
  phases,
  expandedPhaseId,
  currentTaskId,
}: {
  phases: PhaseResponse[];
  expandedPhaseId?: string | null;
  currentTaskId?: string | null;
}) {
  if (phases.length === 0) {
    return (
      <p className="text-sm text-ink-muted">
        Nenhuma fase registrada ainda. O roadmap Ã© criado manualmente â€” nenhuma IA o gera.
      </p>
    );
  }

  return (
    <ol className="space-y-1">
      {phases.map((phase) => {
        const tone = phaseTone(phase.status);
        const expanded = expandedPhaseId === phase.id || phase.status === "IN_PROGRESS";
        return (
          <li key={phase.id}>
            <div className="flex items-center gap-3 py-1">
              <span className={`w-4 text-center font-mono ${MARK_COLOR[tone]}`}>
                {PHASE_MARK[phase.status]}
              </span>
              <span
                className={`flex-1 truncate ${
                  phase.status === "IN_PROGRESS" ? "font-semibold text-white" : "text-ink"
                }`}
              >
                {phase.title}
              </span>
              <span className="font-mono text-[11px] text-ink-faint">
                {phase.completedTasks}/{phase.totalTasks}
              </span>
            </div>

            {expanded && phase.tasks.length > 0 ? (
              <ul className="mb-2 ml-4 space-y-0.5 border-l border-edge pl-4">
                {phase.tasks.map((task) => {
                  const taskColour = taskTone(task.status);
                  return (
                    <li key={task.id} className="flex items-center gap-3 py-0.5 text-sm">
                      <span className={`w-4 text-center font-mono ${MARK_COLOR[taskColour]}`}>
                        {TASK_MARK[task.status]}
                      </span>
                      <span
                        className={`flex-1 truncate ${
                          task.id === currentTaskId ? "text-white" : "text-ink-muted"
                        }`}
                      >
                        {task.title}
                      </span>
                      {task.id === currentTaskId ? (
                        <span className="font-mono text-[10px] text-accent-soft">atual</span>
                      ) : null}
                    </li>
                  );
                })}
              </ul>
            ) : null}
          </li>
        );
      })}
    </ol>
  );
}

const PRIORITY_TONE: Record<NextStepResponse["priority"], Tone> = {
  CRITICAL: "bad",
  HIGH: "warn",
  MEDIUM: "accent",
  LOW: "idle",
};

/**
 * The next step, with its reasoning.
 *
 * The "por quÃª" is not decoration: it is the difference between guidance the user can check and a
 * suggestion they have to take on faith.
 */
export function NextStepCard({
  nextStep,
  action,
}: {
  nextStep: NextStepResponse;
  action?: React.ReactNode;
}) {
  return (
    <article className="card border-accent/50 bg-accent/[0.07] p-6">
      <div className="flex items-start justify-between gap-3">
        <p className="label">prÃ³ximo passo</p>
        <Pill tone={PRIORITY_TONE[nextStep.priority]}>{nextStep.type}</Pill>
      </div>

      <h2 className="mt-3 text-xl font-bold text-white">{nextStep.title}</h2>

      <p className="mt-4 label">por quÃª?</p>
      <p className="mt-1 text-sm leading-6 text-ink">{nextStep.reason}</p>

      {nextStep.blockingIssues.length > 0 ? (
        <>
          <p className="mt-4 label">o que estÃ¡ no caminho</p>
          <ul className="mt-1 space-y-1 text-sm text-signal-warn">
            {nextStep.blockingIssues.map((issue) => (
              <li key={issue}>Â· {issue}</li>
            ))}
          </ul>
        </>
      ) : null}

      {nextStep.requiredActions.length > 0 ? (
        <>
          <p className="mt-4 label">aÃ§Ãµes</p>
          <ol className="mt-1 space-y-1 text-sm text-ink-muted">
            {nextStep.requiredActions.map((step, index) => (
              <li key={step}>
                {index + 1}. {step}
              </li>
            ))}
          </ol>
        </>
      ) : null}

      {action ? <div className="mt-5">{action}</div> : null}
    </article>
  );
}


/** A safe, actionable state for failed authenticated reads. */
export function LoadFailure({
  reason,
  message,
}: {
  reason: "unreachable" | "unauthenticated" | "denied" | "error";
  message: string;
}) {
  const title =
    reason === "unreachable"
      ? "A API do VibeCode não está respondendo"
      : reason === "unauthenticated"
        ? "Sua sessão expirou"
        : reason === "denied"
          ? "Projeto não encontrado"
          : "Não foi possível carregar estes dados";

  return (
    <div className="card border-signal-warn/40 bg-signal-warn/[0.06] p-6">
      <p className="font-semibold text-white">{title}</p>
      <p className="mt-2 text-sm leading-6 text-ink-muted">{message}</p>
    </div>
  );
}