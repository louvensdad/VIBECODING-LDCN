"use client";

import { useState } from "react";
import type { GeneratedPromptResponse, PromptType, TaskSummary } from "@vibecode/contracts";
import { api } from "@/lib/api-client";

const PROMPT_TYPES: PromptType[] = [
  "START_TASK",
  "CONTINUE_TASK",
  "FIX_ERROR",
  "VALIDATE_RESULT",
  "MODEL_HANDOFF",
  "ASK_FOR_EVIDENCE",
  "RESOLVE_BLOCKER",
];

/**
 * Generates the prompt and hands it over for the user to paste wherever they want.
 *
 * There is no "send to Claude" button, and that is the point of this phase: the platform produces
 * the prompt, the user chooses the tool. No provider is integrated.
 */
export function PromptPanel({
  projectId,
  tasks,
  suggestedType,
  defaultTaskId,
}: {
  projectId: string;
  tasks: TaskSummary[];
  suggestedType?: PromptType;
  defaultTaskId?: string | null;
}) {
  const [type, setType] = useState<PromptType | "">(suggestedType ?? "");
  const [taskId, setTaskId] = useState(defaultTaskId ?? "");
  const [prompt, setPrompt] = useState<GeneratedPromptResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  async function generate() {
    setBusy(true);
    setError(null);
    setCopied(false);
    try {
      setPrompt(
        await api.generatePrompt(projectId, {
          taskId: taskId || null,
          type: type || null,
        }),
      );
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Falha ao gerar o prompt.");
    } finally {
      setBusy(false);
    }
  }

  async function copy() {
    if (!prompt) {
      return;
    }
    try {
      await navigator.clipboard.writeText(prompt.content);
      setCopied(true);
    } catch {
      setError("O navegador bloqueou a cópia. Selecione o texto e copie manualmente.");
    }
  }

  return (
    <section className="card p-6">
      <h2 className="text-lg font-semibold text-white">Gerar prompt</h2>
      <p className="mt-1 text-sm text-ink-muted">
        Montado a partir do estado registrado do projeto. Nenhum modelo é chamado aqui.
      </p>

      <div className="mt-5 grid gap-3 sm:grid-cols-2">
        <label className="block">
          <span className="label">prompt type</span>
          <select
            value={type}
            onChange={(event) => setType(event.target.value as PromptType | "")}
            className="mt-1.5 w-full rounded-lg border border-edge bg-surface-sunken px-3 py-2 text-sm text-ink"
          >
            <option value="">
              {suggestedType ? `recomendado (${suggestedType})` : "recomendado"}
            </option>
            {PROMPT_TYPES.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>

        <label className="block">
          <span className="label">task</span>
          <select
            value={taskId}
            onChange={(event) => setTaskId(event.target.value)}
            className="mt-1.5 w-full rounded-lg border border-edge bg-surface-sunken px-3 py-2 text-sm text-ink"
          >
            <option value="">tarefa recomendada</option>
            {tasks.map((task) => (
              <option key={task.id} value={task.id}>
                {task.title}
              </option>
            ))}
          </select>
        </label>
      </div>

      <button
        type="button"
        onClick={generate}
        disabled={busy}
        className="mt-4 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-accent-soft disabled:opacity-40"
      >
        {busy ? "Gerando…" : "Gerar prompt"}
      </button>

      {error ? <p className="mt-4 text-sm text-signal-bad">{error}</p> : null}

      {prompt ? (
        <div className="mt-6 border-t border-edge pt-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <span className="label">prompt type</span>
              <p className="font-mono text-sm text-accent-soft">{prompt.type}</p>
            </div>
            <div>
              <span className="label">task</span>
              <p className="text-sm text-ink">{prompt.taskTitle ?? "—"}</p>
            </div>
            <button
              type="button"
              onClick={copy}
              className="rounded-lg border border-accent/40 bg-accent/10 px-4 py-2 text-sm font-semibold text-accent-soft transition-colors hover:bg-accent/20"
            >
              {copied ? "Copiado ✓" : "Copiar prompt"}
            </button>
          </div>

          <p className="mt-4 label">context sources</p>
          <div className="mt-1.5 flex flex-wrap gap-2">
            {prompt.contextSources.map((source) => (
              <span
                key={source}
                className="rounded border border-edge bg-surface-sunken px-2 py-0.5 font-mono text-[11px] text-ink-muted"
              >
                {source}
              </span>
            ))}
          </div>

          <p className="mt-4 label">generated prompt</p>
          <pre className="mt-1.5 max-h-[28rem] overflow-auto whitespace-pre-wrap rounded-lg border border-edge bg-surface-sunken p-4 font-mono text-xs leading-5 text-ink">
            {prompt.content}
          </pre>

          <p className="mt-3 font-mono text-[11px] text-ink-faint">
            Cole em Claude, ChatGPT, Gemini ou onde preferir. O VibeCode não envia nada por você.
          </p>
        </div>
      ) : null}
    </section>
  );
}
