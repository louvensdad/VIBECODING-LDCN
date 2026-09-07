import type {
  SecurityAssessmentResponse,
  SecurityFindingStatus,
  SecurityGateStatus,
  SecuritySeverity,
} from "@vibecode/contracts";
import { Pill, type Tone } from "./card";

export function severityTone(severity: SecuritySeverity): Tone {
  switch (severity) {
    case "CRITICAL":
    case "HIGH":
      return "bad";
    case "MEDIUM":
      return "warn";
    default:
      return "idle";
  }
}

export function gateTone(gate: SecurityGateStatus): Tone {
  switch (gate) {
    case "PASS":
      return "ok";
    case "WARNING":
      return "warn";
    case "REQUIRES_APPROVAL":
      return "warn";
    case "BLOCKED":
      return "bad";
    default:
      return "idle";
  }
}

export function statusTone(status: SecurityFindingStatus): Tone {
  switch (status) {
    case "OPEN":
      return "bad";
    case "ACKNOWLEDGED":
      return "warn";
    case "RESOLVED":
      return "ok";
    default:
      return "idle";
  }
}

/**
 * Shown on every workspace page while a critical problem is open.
 *
 * It is deliberately hard to ignore: a critical finding means the platform is refusing to hand
 * project context to an external model, and a user who does not know that will read every other
 * refusal as a bug.
 */
export function CriticalSecurityBanner({
  assessment,
  href,
}: {
  assessment: SecurityAssessmentResponse | null;
  href: string;
}) {
  if (!assessment || assessment.critical === 0) {
    return null;
  }

  return (
    <div className="mb-6 rounded-card border border-signal-bad/50 bg-signal-bad/[0.08] p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <p className="font-semibold text-signal-bad">
            {assessment.critical} problema(s) crítico(s) de segurança em aberto
          </p>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            {assessment.blockingReasons[0] ??
              "A geração de prompts está bloqueada até que sejam resolvidos."}
          </p>
        </div>
        <a
          href={href}
          className="shrink-0 rounded-lg border border-signal-bad/40 bg-signal-bad/10 px-4 py-2 text-sm font-semibold text-signal-bad"
        >
          Ver segurança
        </a>
      </div>
    </div>
  );
}

/** Score, gate and the counts behind them. */
export function SecurityScoreCard({
  assessment,
}: {
  assessment: SecurityAssessmentResponse;
}) {
  const scoreColour =
    assessment.score >= 85
      ? "text-signal-ok"
      : assessment.score >= 60
        ? "text-signal-warn"
        : "text-signal-bad";

  return (
    <article className="card p-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="label">security score</p>
          <p className={`mt-2 text-4xl font-bold ${scoreColour}`}>{assessment.score}</p>
          {/* Not a percentage of safety: it is an operational indicator derived from open findings. */}
          <p className="mt-1 font-mono text-[11px] text-ink-faint">
            indicador operacional · 0–100
          </p>
        </div>
        <div className="text-right">
          <p className="label">gate</p>
          <div className="mt-2">
            <Pill tone={gateTone(assessment.gateStatus)}>{assessment.gateStatus}</Pill>
          </div>
          <p className="mt-2 font-mono text-[11px] text-ink-faint">
            {assessment.canProceed ? "pode prosseguir" : "avanço bloqueado"}
          </p>
        </div>
      </div>

      <dl className="mt-6 grid grid-cols-4 gap-3 border-t border-edge pt-5">
        {(
          [
            ["critical", assessment.critical, "text-signal-bad"],
            ["high", assessment.high, "text-signal-bad"],
            ["medium", assessment.medium, "text-signal-warn"],
            ["low", assessment.low, "text-ink-muted"],
          ] as const
        ).map(([label, value, colour]) => (
          <div key={label}>
            <dt className="label">{label}</dt>
            <dd className={`mt-1 text-2xl font-bold ${value > 0 ? colour : "text-ink-faint"}`}>
              {value}
            </dd>
          </div>
        ))}
      </dl>

      {assessment.blockingReasons.length > 0 ? (
        <>
          <p className="mt-5 label">motivos do bloqueio</p>
          <ul className="mt-1 space-y-1 text-sm text-signal-bad">
            {assessment.blockingReasons.map((reason) => (
              <li key={reason}>· {reason}</li>
            ))}
          </ul>
        </>
      ) : null}

      {assessment.warnings.length > 0 ? (
        <>
          <p className="mt-5 label">avisos</p>
          <ul className="mt-1 space-y-1 text-sm text-signal-warn">
            {assessment.warnings.map((warning) => (
              <li key={warning}>· {warning}</li>
            ))}
          </ul>
        </>
      ) : null}
    </article>
  );
}
