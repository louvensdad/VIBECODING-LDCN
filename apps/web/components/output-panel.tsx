"use client";

import { useState } from "react";
import type {
  AnalysisResponse,
  EvidenceType,
  OutputAnalysisResponse,
  TaskSummary,
} from "@vibecode/contracts";
import { api } from "@/lib/api-client";
import { Pill } from "./card";
import { analysisTone } from "./workflow";

const EVIDENCE_TYPES: EvidenceType[] = [
  "LLM_RESPONSE",
  "TERMINAL_OUTPUT",
  "BUILD_RESULT",
  "TEST_RESULT",
  "ERROR_LOG",
  "HTTP_RESPONSE",
  "DATABASE_RESULT",
  "USER_CONFIRMATION",
  "GENERIC_OUTPUT",
];

interface Result {
  analysis: AnalysisResponse;
  taskStatus?: string;
  missing: string[];
  stored: boolean;
}

/**
 * Where the user pastes what came back from a model, a terminal, a build or a test run.
 *
 * Two distinct actions, kept separate on purpose:
 * - **Analisar** looks at the text and stores nothing.
 * - **Registrar** stores it as evidence against a task, and the verdict may move that task.
 */
export function OutputPanel({
  projectId,
  tasks,
  defaultTaskId,
}: {
  projectId: string;
  tasks: TaskSummary[];
  defaultTaskId?: string | null;
}) {
  const [content, setContent] = useState("");
  const [type, setType] = useState<EvidenceType>("BUILD_RESULT");
  const [taskId, setTaskId] = useState(defaultTaskId ?? tasks[0]?.id ?? "");
  const [source, setSource] = useState("user");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<Result | null>(null);

  async function run(store: boolean) {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      if (store) {
        if (!taskId) {
          setError("Escolha a tarefa a que esta saída pertence.");
          return;
        }
        const recorded = await api.recordEvidence(projectId, taskId, {
          type,
          rawContent: content,
          source: source.trim() || "user",
        });
        setResult({
          analysis: recorded.analysis,
          taskStatus: recorded.taskStatus,
          missing: recorded.missingForCompletion,
          stored: true,
        });
      } else {
        const analysis: OutputAnalysisResponse = await api.analyzeOutput(projectId, {
          content,
        });
        setResult({
          analysis: {
            status: analysis.status,
            summary: analysis.summary,
            signals: analysis.signals,
            shouldContinue: analysis.shouldContinue,
            requiresCorrection: analysis.requiresCorrection,
          },
          missing: [],
          stored: false,
        });
      }
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Falha ao processar a saída.");
    } finally {
      setBusy(false);
    }
  }

  const disabled = busy || content.trim().length === 0;

  return (
    <section className="card p-6">
      <h2 className="text-lg font-semibold text-white">Registrar saída</h2>
      <p className="mt-1 text-sm text-ink-muted">
        Cole a resposta de um modelo, um log de terminal, um build, um teste ou um erro.
      </p>

      <div className="mt-5 grid gap-3 sm:grid-cols-3">
        <label className="block">
          <span className="label">tipo da evidência</span>
          <select
            value={type}
            onChange={(event) => setType(event.target.value as EvidenceType)}
            className="mt-1.5 w-full rounded-lg border border-edge bg-surface-sunken px-3 py-2 text-sm text-ink"
          >
            {EVIDENCE_TYPES.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>

        <label className="block">
          <span className="label">tarefa relacionada</span>
          <select
            value={taskId}
            onChange={(event) => setTaskId(event.target.value)}
            className="mt-1.5 w-full rounded-lg border border-edge bg-surface-sunken px-3 py-2 text-sm text-ink"
          >
            {tasks.length === 0 ? <option value="">(nenhuma tarefa)</option> : null}
            {tasks.map((task) => (
              <option key={task.id} value={task.id}>
                {task.title}
              </option>
            ))}
          </select>
        </label>

        <label className="block">
          <span className="label">fonte</span>
          <input
            value={source}
            onChange={(event) => setSource(event.target.value)}
            placeholder="claude, maven, terminal…"
            className="mt-1.5 w-full rounded-lg border border-edge bg-surface-sunken px-3 py-2 text-sm text-ink placeholder:text-ink-faint"
          />
        </label>
      </div>

      <textarea
        value={content}
        onChange={(event) => setContent(event.target.value)}
        rows={10}
        spellCheck={false}
        placeholder="Cole aqui a saída completa, sem resumir."
        className="mt-4 w-full rounded-lg border border-edge bg-surface-sunken p-4 font-mono text-xs leading-5 text-ink placeholder:text-ink-faint"
      />

      <div className="mt-4 flex flex-wrap gap-3">
        <button
          type="button"
          disabled={disabled}
          onClick={() => run(true)}
          className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-accent-soft disabled:cursor-not-allowed disabled:opacity-40"
        >
          {busy ? "Processando…" : "Registrar evidência"}
        </button>
        <button
          type="button"
          disabled={disabled}
          onClick={() => run(false)}
          className="rounded-lg border border-edge-strong px-4 py-2 text-sm font-semibold text-ink transition-colors hover:border-accent/60 disabled:cursor-not-allowed disabled:opacity-40"
        >
          Analisar sem salvar
        </button>
      </div>

      {error ? <p className="mt-4 text-sm text-signal-bad">{error}</p> : null}

      {result ? <AnalysisResult result={result} /> : null}
    </section>
  );
}

function AnalysisResult({ result }: { result: Result }) {
  const { analysis } = result;
  return (
    <div className="mt-6 border-t border-edge pt-5">
      <div className="flex flex-wrap items-center gap-3">
        <span className="label">status</span>
        <Pill tone={analysisTone(analysis.status)}>{analysis.status}</Pill>
        {result.stored ? (
          <span className="font-mono text-[11px] text-ink-faint">
            evidência salva · tarefa agora {result.taskStatus}
          </span>
        ) : (
          <span className="font-mono text-[11px] text-ink-faint">não salvo</span>
        )}
      </div>

      <p className="mt-3 label">summary</p>
      <p className="mt-1 text-sm leading-6 text-ink">{analysis.summary}</p>

      <p className="mt-4 label">signals</p>
      <div className="mt-1.5 flex flex-wrap gap-2">
        {analysis.signals.length === 0 ? (
          <span className="text-sm text-ink-faint">nenhum sinal técnico encontrado</span>
        ) : (
          analysis.signals.map((signal) => (
            <span
              key={signal}
              className="rounded border border-edge bg-surface-sunken px-2 py-0.5 font-mono text-[11px] text-ink-muted"
            >
              {signal}
            </span>
          ))
        )}
      </div>

      <p className="mt-4 label">recommendation</p>
      <p className="mt-1 text-sm leading-6 text-ink-muted">
        {analysis.requiresCorrection
          ? "Corrija antes de avançar. Nenhuma nova funcionalidade deve começar agora."
          : analysis.shouldContinue
            ? "Há evidência técnica de sucesso. É seguro seguir para o próximo passo."
            : "Sem evidência suficiente. Peça o resultado real de build ou testes."}
      </p>

      {result.missing.length > 0 ? (
        <>
          <p className="mt-4 label">falta para concluir a tarefa</p>
          <ul className="mt-1 space-y-1 text-sm text-signal-warn">
            {result.missing.map((item) => (
              <li key={item}>· {item}</li>
            ))}
          </ul>
        </>
      ) : null}
    </div>
  );
}
