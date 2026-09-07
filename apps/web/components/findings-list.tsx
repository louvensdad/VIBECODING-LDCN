"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { SecurityFindingResponse } from "@vibecode/contracts";
import { api, ApiRequestError } from "@/lib/api-client";
import { Pill } from "./card";
import { severityTone, statusTone } from "./security";

/**
 * The findings list, with the decisions a person can take on each one.
 *
 * Accepting a risk on a CRITICAL finding is not offered, because the backend refuses it — showing
 * a button that always fails would just teach the user to distrust the interface.
 */
export function FindingsList({
  projectId,
  findings,
}: {
  projectId: string;
  findings: SecurityFindingResponse[];
}) {
  if (findings.length === 0) {
    return (
      <div className="card p-6">
        <p className="font-semibold text-white">Nenhum problema encontrado</p>
        <p className="mt-2 text-sm leading-6 text-ink-muted">
          Evidências e prompts são inspecionados automaticamente. Nada foi sinalizado até agora.
        </p>
      </div>
    );
  }

  return (
    <ul className="space-y-3">
      {findings.map((finding) => (
        <FindingCard key={finding.id} projectId={projectId} finding={finding} />
      ))}
    </ul>
  );
}

function FindingCard({
  projectId,
  finding,
}: {
  projectId: string;
  finding: SecurityFindingResponse;
}) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reason, setReason] = useState("");

  const settled =
    finding.status === "RESOLVED" ||
    finding.status === "FALSE_POSITIVE" ||
    finding.status === "ACCEPTED_RISK";

  // The backend rejects accepting a critical risk; do not offer it here either.
  const canAcceptRisk = !settled && finding.severity !== "CRITICAL";

  async function act(action: "acknowledge" | "resolve" | "accept-risk" | "false-positive") {
    setBusy(true);
    setError(null);
    try {
      if (action === "acknowledge") {
        await api.acknowledgeFinding(projectId, finding.id);
      } else if (action === "resolve") {
        await api.resolveFinding(projectId, finding.id, { reason: reason || null });
      } else if (action === "accept-risk") {
        await api.acceptFindingRisk(projectId, finding.id, { reason: reason || null });
      } else {
        await api.markFindingFalsePositive(projectId, finding.id, { reason: reason || null });
      }
      setReason("");
      router.refresh();
    } catch (caught) {
      setError(
        caught instanceof ApiRequestError ? caught.message : "Não foi possível registrar a decisão.",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <li className="card p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-semibold text-white">{finding.title}</p>
          <p className="mt-1 font-mono text-[11px] text-ink-faint">
            {finding.ruleId} · {finding.category} · {finding.sourceType}
            {finding.occurrenceCount > 1 ? ` · ${finding.occurrenceCount} ocorrências` : null}
          </p>
        </div>
        <div className="flex shrink-0 gap-2">
          <Pill tone={severityTone(finding.severity)}>{finding.severity}</Pill>
          <Pill tone={statusTone(finding.status)}>{finding.status}</Pill>
        </div>
      </div>

      <p className="mt-3 text-sm leading-6 text-ink-muted">{finding.description}</p>

      <p className="mt-4 label">evidência (já redigida)</p>
      <pre className="mt-1.5 overflow-x-auto rounded-lg border border-edge bg-surface-sunken p-3 font-mono text-xs text-ink">
        {finding.evidence}
      </pre>
      {finding.location ? (
        <p className="mt-1.5 font-mono text-[11px] text-ink-faint">onde: {finding.location}</p>
      ) : null}

      <p className="mt-4 label">ação recomendada</p>
      <p className="mt-1 text-sm leading-6 text-ink">{finding.recommendation}</p>

      {finding.resolutionReason ? (
        <p className="mt-3 font-mono text-[11px] text-ink-faint">
          decisão registrada: {finding.resolutionReason}
        </p>
      ) : null}

      {!settled ? (
        <div className="mt-5 border-t border-edge pt-4">
          <label className="block">
            <span className="label">justificativa (registrada na auditoria)</span>
            <input
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              placeholder="Por que esta decisão?"
              className="mt-1.5 w-full rounded-lg border border-edge bg-surface-sunken px-3 py-2 text-sm text-ink placeholder:text-ink-faint"
            />
          </label>

          <div className="mt-3 flex flex-wrap gap-2">
            {finding.status === "OPEN" ? (
              <ActionButton busy={busy} onClick={() => act("acknowledge")}>
                Reconhecer
              </ActionButton>
            ) : null}
            <ActionButton busy={busy} onClick={() => act("resolve")} primary>
              Resolver
            </ActionButton>
            <ActionButton busy={busy} onClick={() => act("false-positive")}>
              Falso positivo
            </ActionButton>
            {canAcceptRisk ? (
              <ActionButton busy={busy} onClick={() => act("accept-risk")}>
                Aceitar risco
              </ActionButton>
            ) : (
              <span className="self-center font-mono text-[11px] text-ink-faint">
                risco CRITICAL não pode ser aceito
              </span>
            )}
          </div>

          {error ? <p className="mt-3 text-sm text-signal-bad">{error}</p> : null}
        </div>
      ) : null}
    </li>
  );
}

function ActionButton({
  children,
  onClick,
  busy,
  primary = false,
}: {
  children: React.ReactNode;
  onClick: () => void;
  busy: boolean;
  primary?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={busy}
      className={
        primary
          ? "rounded-lg bg-accent px-3.5 py-2 text-sm font-semibold text-white transition-colors hover:bg-accent-soft disabled:opacity-40"
          : "rounded-lg border border-edge-strong px-3.5 py-2 text-sm text-ink transition-colors hover:border-accent/60 disabled:opacity-40"
      }
    >
      {children}
    </button>
  );
}
