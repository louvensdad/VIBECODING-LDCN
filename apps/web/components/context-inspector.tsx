"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type {
  ContextItemResponse,
  ContextPackResponse,
  ContextSourceType,
} from "@vibecode/contracts";
import { api, ApiRequestError, ApiUnreachableError } from "@/lib/api-client";
import { Pill, ProgressBar, SectionCard, type Tone } from "./card";

/**
 * The Context Inspector.
 *
 * What this screen is for: showing a compiled pack exactly as the API returned it, so someone can
 * see what a model *would* be given before any model is involved. Nothing here calls a provider,
 * and there is deliberately no control that could — see {@link ProviderStatus}.
 *
 * Everything shown is the backend's. Redaction, budget arithmetic, admission decisions and
 * provenance all happen server-side and arrive as fields; this component formats them and does not
 * recompute any of them. In particular it runs no redactor of its own: a second, weaker pass in
 * JavaScript would produce a screen that disagrees with the stored pack while looking more
 * trustworthy than it is.
 */

/**
 * One row of the pack index.
 *
 * Derived from the contract with `Pick` rather than written out, so renaming a field on the wire
 * breaks this too. It is a projection of `ContextPackResponse`, not a second definition of it: the
 * server drops everything else before the list crosses into the browser (see the page), and the
 * pack being inspected is read whole from its own route.
 */
export type ContextPackRow = Pick<
  ContextPackResponse,
  "packId" | "taskReference" | "assembledAt"
> & {
  items: ContextPackResponse["usage"]["items"];
  characters: ContextPackResponse["usage"]["characters"];
};

/** How much of an item's content is rendered before the reader asks for the rest. */
const PREVIEW_CHARACTERS = 600;

/**
 * How many item cards are rendered at once.
 *
 * The per-item cap below bounds one card; it composes into no bound at all across a pack, and the
 * API will compile up to `MAX_REQUESTABLE_ITEMS` (1000) of them. A thousand cards is a page nobody
 * reads and a browser that struggles to paint it, so the list is cut and says where it was cut.
 */
const MAX_RENDERED_ITEMS = 100;

/**
 * How long the UI waits for a pack before it offers the reader a way out.
 *
 * This bounds the *screen*, not the request: there is no `AbortSignal` on the shared transport, and
 * adding one would change how every other page in the app fails. What it removes is the state with
 * no exit — a connection the API accepts and never answers would otherwise leave the detail pane
 * loading forever, and the only escape a reload.
 */
const DETAIL_TIMEOUT_MS = 20_000;

/**
 * The most content this component will put in the DOM for one item, even expanded.
 *
 * There is a registered debt that context content has no global size limit, so an item's size is
 * whatever the collector produced. A single string of a few million characters in one text node is
 * enough to make the tab unresponsive, and no user asked to scroll through it. The view is bounded
 * and says so; the pack itself is untouched. Raising this is not the fix for that debt — the limit
 * belongs at the engine, which is not this Wave's to change.
 */
const MAX_RENDERED_CHARACTERS = 20_000;

/** Grouped digits without `toLocaleString`, whose output differs between Node and the browser. */
function groupDigits(value: number): string {
  return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, " ");
}

/**
 * An instant as a fixed UTC string.
 *
 * Deliberately not locale-formatted. This component renders on the server and again in the
 * browser, and a locale- or timezone-dependent string differs between the two, which React reports
 * as a hydration error. UTC is the same in both.
 */
function formatInstant(iso: string): string {
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) {
    return iso;
  }
  return `${parsed.toISOString().slice(0, 19).replace("T", " ")} UTC`;
}

function shortId(id: string): string {
  return id.length > 12 ? `${id.slice(0, 8)}…` : id;
}

/** A percentage for the budget bars; a zero ceiling is shown as full rather than as NaN. */
function usedPercent(used: number, ceiling: number): number {
  if (ceiling <= 0) {
    return 100;
  }
  return Math.min(100, (used / ceiling) * 100);
}

function budgetTone(percent: number): Tone {
  if (percent >= 90) {
    return "bad";
  }
  if (percent >= 70) {
    return "warn";
  }
  return "ok";
}

/** Turns any thrown API failure into a sentence, never a stack trace. */
function describeError(error: unknown): string {
  if (error instanceof ApiUnreachableError) {
    return error.message;
  }
  if (error instanceof ApiRequestError) {
    if (error.isNotFound) {
      return "Este ContextPack não existe neste projeto.";
    }
    if (error.isUnauthenticated) {
      return "Sua sessão expirou. Entre novamente para continuar.";
    }
    return error.message;
  }
  return "Não foi possível completar a operação.";
}

/**
 * Rejects with the app's own unreachable error if the work has not settled in time.
 *
 * The underlying request is left running — it cannot be cancelled through this transport — so this
 * is honest only because the failure it produces is the retryable one. Nothing is reported as
 * having succeeded or failed on the server; the screen simply stops waiting.
 */
function withTimeout<T>(work: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new ApiUnreachableError(new Error("timeout"))), ms);
    work.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}

type DetailState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "loaded"; pack: ContextPackResponse }
  | { status: "error"; message: string };

export function ContextInspector({
  projectId,
  initialPacks,
}: {
  projectId: string;
  initialPacks: ContextPackRow[];
}) {
  const [packs, setPacks] = useState<ContextPackRow[]>(initialPacks);
  const [selectedPackId, setSelectedPackId] = useState<string | null>(
    initialPacks[0]?.packId ?? null,
  );
  const [detail, setDetail] = useState<DetailState>({ status: "idle" });

  /**
   * Which detail request the UI is still interested in.
   *
   * Selecting a second pack before the first response lands would otherwise let the slower request
   * paint over the newer selection. The counter is compared on arrival and stale responses are
   * dropped.
   */
  const requestToken = useRef(0);

  const loadPack = useCallback(
    async (packId: string) => {
      const token = ++requestToken.current;
      setDetail({ status: "loading" });
      try {
        // The list route already returns whole packs, but the pack being inspected is read from
        // its own route: that is the endpoint whose ownership rules are the authority on whether
        // this pack may be shown at all, and reading it here means the inspector never displays a
        // pack the detail route would refuse.
        const pack = await withTimeout(api.getContextPack(projectId, packId), DETAIL_TIMEOUT_MS);
        if (token !== requestToken.current) {
          return;
        }
        // Defence in depth. The API scopes a pack to its project and answers 404 otherwise, so a
        // mismatch is unreachable today; showing it anyway would be the one failure this screen
        // must never have, so it is refused rather than rendered.
        if (pack.projectId !== projectId) {
          setDetail({
            status: "error",
            message: "Este ContextPack pertence a outro projeto e não será exibido.",
          });
          return;
        }
        setDetail({ status: "loaded", pack });
      } catch (error) {
        if (token === requestToken.current) {
          setDetail({ status: "error", message: describeError(error) });
        }
      }
    },
    [projectId],
  );

  // Selection is reset by remounting, not by an effect: the page renders this component with
  // `key={projectId}`, so navigating to another project builds a new component with new state
  // rather than carrying a pack id across the boundary. A pack id that outlived its project would
  // either 404 or, if ids were ever guessable, read as though it belonged to the project now on
  // screen. Doing it with a key means there is no window in which the old id is still selected.
  useEffect(() => {
    if (selectedPackId) {
      void loadPack(selectedPackId);
    } else {
      setDetail({ status: "idle" });
    }
  }, [selectedPackId, loadPack]);

  const handleCompiled = useCallback((pack: ContextPackResponse) => {
    // Only the row is kept. The compiled pack's own content is not held in state: the detail pane
    // reads it back from the pack route like any other selection, so there is one path to a
    // displayed pack rather than two.
    setPacks((current) => [
      {
        packId: pack.packId,
        taskReference: pack.taskReference,
        assembledAt: pack.assembledAt,
        items: pack.usage.items,
        characters: pack.usage.characters,
      },
      ...current,
    ]);
    setSelectedPackId(pack.packId);
  }, []);

  return (
    <div className="mt-8 space-y-6">
      <ProviderStatus />

      <CompilePanel projectId={projectId} onCompiled={handleCompiled} />

      {packs.length === 0 ? (
        <EmptyState />
      ) : (
        <div className="grid gap-6 lg:grid-cols-[19rem_minmax(0,1fr)]">
          <PackList packs={packs} selectedPackId={selectedPackId} onSelect={setSelectedPackId} />
          <div className="min-w-0">
            <PackDetail
              state={detail}
              onRetry={() => {
                if (selectedPackId) {
                  void loadPack(selectedPackId);
                }
              }}
            />
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * The statement that this pack has not been anywhere.
 *
 * Compiled context and sent context are different things, and the gap between them is the whole
 * point of inspecting a pack first. This panel exists so that nobody reads a screen full of
 * assembled context as evidence that a model already received it.
 */
function ProviderStatus() {
  return (
    <section className="card border-edge-strong p-5" data-testid="provider-status">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="label">execução por provedor</h2>
        <Pill tone="idle">NÃO ATIVA</Pill>
      </div>
      <p className="mt-3 text-sm leading-6 text-ink-muted">
        <strong className="text-ink">Não há execução por provedor nesta versão</strong>: esta tela
        não envia contexto a nenhum modelo, e não existe no cliente caminho que possa fazê-lo.
        Compilar contexto e enviar contexto são coisas diferentes — o que está abaixo foi montado e
        guardado pela plataforma.
      </p>
    </section>
  );
}

/**
 * Compiles a new pack.
 *
 * The only write this screen makes, and it reaches exactly one route. The task reference is the
 * caller's; every other property of the result — which items were admitted, under which rule, how
 * large they are — is the engine's, and there is no field here that could reach any of it.
 */
function CompilePanel({
  projectId,
  onCompiled,
}: {
  projectId: string;
  onCompiled: (pack: ContextPackResponse) => void;
}) {
  const [taskReference, setTaskReference] = useState("");
  const [state, setState] = useState<
    { status: "idle" } | { status: "compiling" } | { status: "error"; message: string }
  >({ status: "idle" });

  const trimmed = taskReference.trim();
  const busy = state.status === "compiling";

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!trimmed || busy) {
      return;
    }
    setState({ status: "compiling" });
    try {
      // Bounded like the detail read, and for the same reason: while this is pending the input and
      // the button are disabled, so a compile that never answers would leave a dead form with no
      // retry — the worse version of the state the detail pane no longer has.
      const pack = await withTimeout(
        api.compileContextPack(projectId, { taskReference: trimmed }),
        DETAIL_TIMEOUT_MS,
      );
      setTaskReference("");
      setState({ status: "idle" });
      onCompiled(pack);
    } catch (error) {
      setState({ status: "error", message: describeError(error) });
    }
  }

  return (
    <SectionCard title="compilar contexto">
      <form onSubmit={submit} className="flex flex-col gap-3 sm:flex-row sm:items-start">
        <div className="min-w-0 flex-1">
          <label htmlFor="taskReference" className="sr-only">
            Tarefa para a qual o contexto será compilado
          </label>
          <input
            id="taskReference"
            name="taskReference"
            value={taskReference}
            onChange={(event) => setTaskReference(event.target.value)}
            placeholder="Para qual tarefa? ex.: implementar autenticação"
            maxLength={500}
            disabled={busy}
            className="w-full rounded-lg border border-edge bg-surface-sunken px-3 py-2 text-sm text-ink outline-none transition-colors placeholder:text-ink-faint focus:border-accent/60 disabled:opacity-60"
          />
        </div>
        <button
          type="submit"
          disabled={!trimmed || busy}
          className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white transition-opacity disabled:cursor-not-allowed disabled:opacity-50"
        >
          {busy ? "Compilando…" : "Compilar"}
        </button>
      </form>

      <p className="mt-3 font-mono text-[11px] leading-5 text-ink-faint">
        Compilar monta um pacote a partir do estado oficial do projeto e o guarda. Um pacote nunca é
        substituído: compilar de novo cria outro registro.
      </p>

      {state.status === "error" ? (
        <p
          role="alert"
          className="mt-3 rounded-lg border border-signal-bad/30 bg-signal-bad/10 px-3 py-2 text-sm text-signal-bad"
        >
          {state.message}
        </p>
      ) : null}
    </SectionCard>
  );
}

function EmptyState() {
  return (
    <div className="card p-8 text-center" data-testid="empty-state">
      <p className="font-semibold text-white">Nenhum ContextPack ainda</p>
      <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-muted">
        Compile o primeiro pacote acima para ver exatamente o que a plataforma reuniu sobre este
        projeto — item por item, com a origem de cada um.
      </p>
    </div>
  );
}

function PackList({
  packs,
  selectedPackId,
  onSelect,
}: {
  packs: ContextPackRow[];
  selectedPackId: string | null;
  onSelect: (packId: string) => void;
}) {
  return (
    <nav aria-label="ContextPacks" className="space-y-2" data-testid="pack-list">
      <p className="label">pacotes ({packs.length})</p>
      {packs.map((pack) => {
        const active = pack.packId === selectedPackId;
        return (
          <button
            key={pack.packId}
            type="button"
            onClick={() => onSelect(pack.packId)}
            aria-current={active ? "true" : undefined}
            className={`w-full rounded-card border p-3 text-left transition-colors ${
              active
                ? "border-accent/60 bg-accent/[0.10]"
                : "border-edge bg-surface/60 hover:border-edge-strong hover:bg-surface-raised/70"
            }`}
          >
            <span className="block truncate font-mono text-[11px] text-accent-soft">
              {shortId(pack.packId)}
            </span>
            <span className="mt-1 block truncate text-sm text-ink">{pack.taskReference}</span>
            <span className="mt-1 block font-mono text-[10px] text-ink-faint">
              {formatInstant(pack.assembledAt)}
            </span>
            <span className="mt-1 block font-mono text-[10px] text-ink-faint">
              {pack.items} itens · {groupDigits(pack.characters)} chars
            </span>
          </button>
        );
      })}
    </nav>
  );
}

function PackDetail({ state, onRetry }: { state: DetailState; onRetry: () => void }) {
  if (state.status === "idle") {
    return (
      <div className="card p-6 text-sm text-ink-muted">Selecione um pacote para inspecioná-lo.</div>
    );
  }

  if (state.status === "loading") {
    return (
      <div className="card p-6" role="status" aria-live="polite" data-testid="detail-loading">
        <p className="text-sm text-ink-muted">Carregando ContextPack…</p>
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div
        className="card border-signal-warn/40 bg-signal-warn/[0.06] p-6"
        role="alert"
        data-testid="detail-error"
      >
        <p className="font-semibold text-white">Não foi possível abrir este ContextPack</p>
        <p className="mt-2 text-sm leading-6 text-ink-muted">{state.message}</p>
        <button
          type="button"
          onClick={onRetry}
          className="mt-4 rounded-lg border border-edge-strong px-3 py-1.5 text-sm text-ink-muted transition-colors hover:text-ink"
        >
          Tentar de novo
        </button>
      </div>
    );
  }

  const { pack } = state;
  return (
    <div className="space-y-6" data-testid="pack-detail">
      <PackSummary pack={pack} />
      <BudgetPanel pack={pack} />
      <SourceBreakdown items={pack.items} />
      <section>
        <p className="label">itens do pacote ({pack.items.length})</p>
        <div className="mt-3 space-y-3">
          {pack.items.slice(0, MAX_RENDERED_ITEMS).map((item, index) => (
            <ItemCard key={item.id} item={item} position={index + 1} total={pack.items.length} />
          ))}
        </div>
        {pack.items.length > MAX_RENDERED_ITEMS ? (
          <p
            className="mt-3 font-mono text-[11px] text-ink-faint"
            data-testid="item-list-extent"
          >
            exibindo {groupDigits(MAX_RENDERED_ITEMS)} de {groupDigits(pack.items.length)} itens —
            o pacote guardado continua completo.
          </p>
        ) : null}
      </section>
    </div>
  );
}

function PackSummary({ pack }: { pack: ContextPackResponse }) {
  const estimate = pack.usage.estimatedTokenCount;

  return (
    <section className="card p-5" data-testid="pack-summary">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="label">contextpack</p>
          <p className="mt-1 break-all font-mono text-sm text-white">{pack.packId}</p>
        </div>
        <RedactionBadge />
      </div>

      <dl className="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Fact label="montado em" value={formatInstant(pack.assembledAt)} />
        <Fact label="itens" value={groupDigits(pack.usage.items)} />
        <Fact label="caracteres" value={groupDigits(pack.usage.characters)} />
        <Fact label="bytes" value={groupDigits(pack.usage.bytes)} />
      </dl>

      <div className="mt-5 border-t border-edge pt-4">
        <p className="label">tarefa</p>
        <p className="mt-1 text-sm leading-6 text-ink">{pack.taskReference}</p>
      </div>

      <div className="mt-4 border-t border-edge pt-4">
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <p className="label">tokens</p>
          <p className="font-mono text-lg text-white" data-testid="estimated-tokens">
            ~{groupDigits(estimate.estimatedTokens)}
          </p>
          <Pill tone="warn">ESTIMADO</Pill>
        </div>
        <p className="mt-2 font-mono text-[11px] leading-5 text-ink-faint">
          Número estimado pela regra <span className="text-ink-muted">{estimate.heuristic}</span>,
          não medido. Um provedor conta tokens com o próprio tokenizador e chegará a outro número.
        </p>
      </div>

      <div className="mt-4 border-t border-edge pt-4">
        <p className="label">impressão do conteúdo</p>
        <p className="mt-1 break-all font-mono text-[11px] text-ink-faint">
          {pack.contentFingerprint}
        </p>
      </div>
    </section>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="label">{label}</dt>
      <dd className="mt-1 font-mono text-sm text-white">{value}</dd>
    </div>
  );
}

/**
 * What the platform actually promises about this content, and no more.
 *
 * The badge says the pack is redacted because it is: the compiler runs redaction before anything
 * measures or stores an item, and an admitted item can only be built from a redacted one. What the
 * badge must not say is that the pack is therefore safe. The redactor matches patterns, and a
 * secret shaped like nothing it knows travels through it untouched. Saying "no sensitive data"
 * here would be claiming a guarantee the engine does not make.
 */
function RedactionBadge() {
  return (
    <div className="text-right" data-testid="redaction-status">
      {/*
        Informational, not a green tick. The tone is deliberate: a success colour beside the word
        REDIGIDO reads as "this pack is safe", which is a stronger claim than a pattern-based
        redactor can support, and it would be read before the sentence underneath qualifying it.
      */}
      <Pill tone="accent">REDIGIDO</Pill>
      <p className="mt-2 max-w-xs text-[11px] leading-5 text-ink-faint">
        A plataforma substitui por <span className="font-mono text-ink-muted">[REDACTED]</span> os
        valores sensíveis que reconhece, antes de gravar qualquer pacote. A detecção é por padrões:
        não é garantia de que nada sensível restou.
      </p>
    </div>
  );
}

function BudgetPanel({ pack }: { pack: ContextPackResponse }) {
  const dimensions = [
    { key: "itens", used: pack.usage.items, ceiling: pack.budget.maxItems },
    { key: "caracteres", used: pack.usage.characters, ceiling: pack.budget.maxCharacters },
    { key: "bytes", used: pack.usage.bytes, ceiling: pack.budget.maxBytes },
  ];

  return (
    <SectionCard title="orçamento">
      <div className="space-y-4" data-testid="budget-panel">
        {dimensions.map((dimension) => {
          const percent = usedPercent(dimension.used, dimension.ceiling);
          const tone = budgetTone(percent);
          return (
            <div key={dimension.key}>
              <div className="flex items-baseline justify-between gap-3">
                <span className="label">{dimension.key}</span>
                <span className="font-mono text-xs text-ink">
                  {groupDigits(dimension.used)} / {groupDigits(dimension.ceiling)}
                  <span
                    className={`ml-2 ${
                      tone === "bad"
                        ? "text-signal-bad"
                        : tone === "warn"
                          ? "text-signal-warn"
                          : "text-ink-faint"
                    }`}
                  >
                    {percent.toFixed(percent >= 10 ? 0 : 1)}%
                  </span>
                </span>
              </div>
              <div className="mt-1.5">
                <ProgressBar value={percent} label={`${dimension.key} usados`} />
              </div>
            </div>
          );
        })}
      </div>

      <p className="mt-4 font-mono text-[11px] leading-5 text-ink-faint">
        O orçamento é um teto, não uma meta. Não há dimensão de tokens: o teto é medido em itens,
        caracteres e bytes — o número de tokens acima é estimativa e não limita nada.
      </p>
    </SectionCard>
  );
}

/** Where this pack's content came from, counted by origin. */
function SourceBreakdown({ items }: { items: ContextItemResponse[] }) {
  const counts = new Map<ContextSourceType, number>();
  for (const item of items) {
    const type = item.provenance.source.type;
    counts.set(type, (counts.get(type) ?? 0) + 1);
  }
  const ordered = [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));

  return (
    <SectionCard title="origens">
      <ul className="flex flex-wrap gap-2" data-testid="source-breakdown">
        {ordered.map(([type, count]) => (
          <li
            key={type}
            className="inline-flex items-center gap-2 rounded-full border border-edge-strong bg-surface-raised px-3 py-1"
          >
            <span className="font-mono text-[11px] text-ink">{type}</span>
            <span className="font-mono text-[11px] text-accent-soft">{count}</span>
          </li>
        ))}
      </ul>
    </SectionCard>
  );
}

/**
 * One admitted item.
 *
 * Collapsed by default. An item's size is whatever its collector produced, and a pack of forty
 * items expanded at once is a page nobody can read and a browser that struggles to paint it.
 */
function ItemCard({
  item,
  position,
  total,
}: {
  item: ContextItemResponse;
  position: number;
  total: number;
}) {
  const [expanded, setExpanded] = useState(false);
  const { source, recordedAt } = item.provenance;

  const oversized = item.content.length > MAX_RENDERED_CHARACTERS;
  const body = expanded
    ? item.content.slice(0, MAX_RENDERED_CHARACTERS)
    : item.content.slice(0, PREVIEW_CHARACTERS);
  const hasMore = item.content.length > PREVIEW_CHARACTERS;

  return (
    <article className="card p-4" data-testid="context-item">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Pill tone="accent">{item.kind}</Pill>
            <span className="font-mono text-[10px] text-ink-faint">
              {position}/{total}
            </span>
          </div>
          <h3 className="mt-2 break-words text-sm font-semibold text-white">{item.label}</h3>
        </div>
        <div className="text-right font-mono text-[10px] leading-5 text-ink-faint">
          <p>{groupDigits(item.characterCount)} chars</p>
          <p>{groupDigits(item.byteCount)} bytes</p>
        </div>
      </header>

      <dl className="mt-3 grid gap-x-6 gap-y-2 border-t border-edge pt-3 sm:grid-cols-2">
        <div>
          <dt className="label">origem</dt>
          <dd className="mt-0.5 font-mono text-[11px] text-ink">
            {source.type}
            <span className="text-ink-faint"> · </span>
            <span className="break-all text-ink-muted">{source.sourceId}</span>
            {source.version === null ? null : (
              <span className="text-ink-faint"> · v{source.version}</span>
            )}
          </dd>
        </div>
        <div>
          <dt className="label">registrado em</dt>
          <dd className="mt-0.5 font-mono text-[11px] text-ink-muted">{formatInstant(recordedAt)}</dd>
        </div>
        <div className="sm:col-span-2">
          <dt className="label">admitido por</dt>
          <dd className="mt-0.5 text-[11px] leading-5 text-ink-muted">
            <span className="font-mono text-ink">{item.admission.policyRuleId}</span>
            <span className="text-ink-faint"> — </span>
            {item.admission.explanation}
          </dd>
        </div>
      </dl>

      <div className="mt-3 border-t border-edge pt-3">
        {/*
          Content is untrusted text: it came from records the user pasted in. It is rendered as a
          text child of <pre>, which React escapes, so a <script> tag or an onerror attribute is
          shown rather than run. There is no dangerouslySetInnerHTML on this screen, and there must
          not be one — the moment content is parsed as HTML, an inspector for untrusted context
          becomes a way to execute it.
        */}
        <pre
          data-testid="item-content"
          className="max-h-96 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-surface-sunken p-3 font-mono text-[11px] leading-5 text-ink-muted [contain:content]"
        >
          {body}
        </pre>

        {hasMore ? (
          <div className="mt-2 flex flex-wrap items-center gap-3">
            <button
              type="button"
              onClick={() => setExpanded((value) => !value)}
              aria-expanded={expanded}
              className="rounded-lg border border-edge-strong px-3 py-1 font-mono text-[11px] text-ink-muted transition-colors hover:text-ink"
            >
              {expanded ? "Recolher" : "Expandir"}
            </button>
            <span className="font-mono text-[10px] text-ink-faint" data-testid="content-extent">
              {expanded
                ? oversized
                  ? `exibindo ${groupDigits(MAX_RENDERED_CHARACTERS)} de ${groupDigits(
                      item.content.length,
                    )} caracteres`
                  : `${groupDigits(item.content.length)} caracteres`
                : `prévia de ${groupDigits(PREVIEW_CHARACTERS)} de ${groupDigits(
                    item.content.length,
                  )} caracteres`}
            </span>
          </div>
        ) : null}
      </div>
    </article>
  );
}
