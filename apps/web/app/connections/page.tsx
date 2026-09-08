"use client";

import { useCallback, useEffect, useState } from "react";
import type {
  ProviderAccountResponse,
  ProviderCatalogEntry,
  ProviderId,
} from "@vibecode/contracts";
import { Pill, type Tone } from "@/components/card";
import { api } from "@/lib/api-client";

/**
 * AI Connections.
 *
 * The one screen in the product where a credential is typed. Three rules shape it:
 *
 * - The credential is held in component state only while the form is open, and cleared the moment
 *   the request resolves — success or failure. It is never written to localStorage,
 *   sessionStorage or IndexedDB, because anything stored there survives the tab, is readable by
 *   any script that gets injected into this origin, and outlives the user's intent.
 * - Nothing that comes back from the server is a credential, so there is nothing here to render
 *   masked. The page shows whether a key exists and when it was last changed.
 * - The status shown is the status the server reports. "Stored" never becomes "connected" on this
 *   screen, because the platform has not contacted the provider and does not know.
 */
export default function ConnectionsPage() {
  const [accounts, setAccounts] = useState<ProviderAccountResponse[] | null>(null);
  const [catalog, setCatalog] = useState<ProviderCatalogEntry[]>([]);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      const [list, providers] = await Promise.all([
        api.listProviderAccounts(),
        api.getProviderCatalog(),
      ]);
      setAccounts(list);
      setCatalog(providers);
      setError(null);
    } catch {
      setError("Não foi possível carregar as conexões.");
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  return (
    <>
      <p className="eyebrow">workspace</p>
      <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">AI Connections</h1>
      <p className="mt-2 max-w-2xl text-ink-muted">
        Suas credenciais de provider. Elas são criptografadas antes de serem armazenadas e não podem
        ser lidas de volta — nem por esta tela, nem por nenhuma API. Para trocar uma chave, envie a
        nova.
      </p>

      {error ? (
        <p className="mt-6 rounded-card border border-signal-bad/30 bg-signal-bad/10 p-4 text-sm text-signal-bad">
          {error}
        </p>
      ) : null}

      <NewConnection catalog={catalog} onCreated={reload} />

      <div className="mt-8 grid gap-4">
        {accounts === null ? (
          <p className="font-mono text-xs text-ink-faint">carregando…</p>
        ) : accounts.length === 0 ? (
          <p className="card p-6 text-sm text-ink-muted">
            Nenhuma conexão ainda. Crie uma acima para guardar uma chave.
          </p>
        ) : (
          accounts.map((account) => (
            <ConnectionCard key={account.id} account={account} onChanged={reload} />
          ))
        )}
      </div>

      <p className="mt-8 max-w-2xl text-xs leading-6 text-ink-faint">
        Guardar uma chave não prova que ela funciona. Nada é enviado ao provider nesta fase, então o
        estado exibido é <span className="font-mono">stored</span>, nunca{" "}
        <span className="font-mono">connected</span>.
      </p>
    </>
  );
}

function NewConnection({
  catalog,
  onCreated,
}: {
  catalog: ProviderCatalogEntry[];
  onCreated: () => Promise<void>;
}) {
  const [provider, setProvider] = useState<ProviderId | "">("");
  const [displayName, setDisplayName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!provider || busy) return;
    setBusy(true);
    setError(null);
    try {
      await api.createProviderAccount({
        provider,
        displayName: displayName.trim() || provider,
        authenticationType: "API_KEY",
      });
      setProvider("");
      setDisplayName("");
      await onCreated();
    } catch {
      setError("Não foi possível criar a conexão.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="card mt-8 grid gap-3 p-5 sm:grid-cols-[1fr_1fr_auto]">
      <label className="grid gap-1.5">
        <span className="label">Provider</span>
        <select
          value={provider}
          onChange={(event) => setProvider(event.target.value as ProviderId)}
          className="rounded-lg border border-edge bg-surface-sunken px-3 py-2 text-sm text-ink"
          required
        >
          <option value="">Escolha…</option>
          {catalog.map((entry) => (
            <option key={entry.id} value={entry.id}>
              {entry.displayName}
            </option>
          ))}
        </select>
      </label>

      <label className="grid gap-1.5">
        <span className="label">Nome</span>
        <input
          value={displayName}
          onChange={(event) => setDisplayName(event.target.value)}
          placeholder="Minha chave de trabalho"
          maxLength={120}
          className="rounded-lg border border-edge bg-surface-sunken px-3 py-2 text-sm text-ink"
        />
      </label>

      <button
        type="submit"
        disabled={busy || !provider}
        className="self-end rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
      >
        {busy ? "Criando…" : "Criar conexão"}
      </button>

      {error ? (
        <p className="text-sm text-signal-bad sm:col-span-3">{error}</p>
      ) : null}
    </form>
  );
}

const STATUS_TONE: Record<ProviderAccountResponse["status"], Tone> = {
  PENDING_CREDENTIAL: "idle",
  CREDENTIAL_STORED_UNVERIFIED: "accent",
  DISABLED: "warn",
};

const STATUS_LABEL: Record<ProviderAccountResponse["status"], string> = {
  PENDING_CREDENTIAL: "sem credencial",
  CREDENTIAL_STORED_UNVERIFIED: "credencial guardada · não verificada",
  DISABLED: "desativada",
};

function ConnectionCard({
  account,
  onChanged,
}: {
  account: ProviderAccountResponse;
  onChanged: () => Promise<void>;
}) {
  const [open, setOpen] = useState(false);

  return (
    <article className="card p-5">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold text-white">{account.displayName}</h2>
          <p className="mt-1 font-mono text-[11px] uppercase tracking-wider text-ink-faint">
            {account.provider} · {account.authenticationType}
          </p>
        </div>
        <Pill tone={STATUS_TONE[account.status]}>{STATUS_LABEL[account.status]}</Pill>
      </header>

      <p className="mt-3 text-sm text-ink-muted">
        {account.hasCredential
          ? `Chave guardada${
              account.credentialUpdatedAt
                ? ` em ${new Date(account.credentialUpdatedAt).toLocaleString("pt-BR")}`
                : ""
            }.`
          : "Nenhuma chave guardada para esta conexão."}
      </p>

      <div className="mt-4 flex flex-wrap gap-2">
        <button
          type="button"
          onClick={() => setOpen((value) => !value)}
          className="rounded-lg border border-edge px-3 py-1.5 text-sm text-ink-muted hover:text-ink"
        >
          {account.hasCredential ? "Substituir chave" : "Adicionar chave"}
        </button>

        {account.hasCredential ? (
          <button
            type="button"
            onClick={async () => {
              await api.removeProviderCredential(account.id);
              await onChanged();
            }}
            className="rounded-lg border border-edge px-3 py-1.5 text-sm text-ink-muted hover:text-ink"
          >
            Remover chave
          </button>
        ) : null}

        {account.status !== "DISABLED" ? (
          <button
            type="button"
            onClick={async () => {
              await api.disableProviderAccount(account.id);
              await onChanged();
            }}
            className="rounded-lg border border-edge px-3 py-1.5 text-sm text-ink-muted hover:text-ink"
          >
            Desativar
          </button>
        ) : null}
      </div>

      {open ? (
        <CredentialForm
          account={account}
          onDone={async () => {
            setOpen(false);
            await onChanged();
          }}
        />
      ) : null}
    </article>
  );
}

function CredentialForm({
  account,
  onDone,
}: {
  account: ProviderAccountResponse;
  onDone: () => Promise<void>;
}) {
  const [credential, setCredential] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // If this component unmounts for any reason, the value goes with it. Nothing outlives the form.
  useEffect(() => () => setCredential(""), []);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busy || credential.length === 0) return;
    setBusy(true);
    setError(null);
    try {
      await api.storeProviderCredential(account.id, { credential });
      await onDone();
    } catch {
      setError("Não foi possível guardar a credencial.");
    } finally {
      // Cleared on both paths. A failed submit is exactly when someone would leave the tab open
      // with the key still sitting in it.
      setCredential("");
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="mt-4 grid gap-3 border-t border-edge pt-4">
      <label className="grid gap-1.5">
        <span className="label">Chave de API</span>
        <input
          type="password"
          value={credential}
          onChange={(event) => setCredential(event.target.value)}
          autoComplete="off"
          spellCheck={false}
          // Off deliberately: a password manager offering to save this would put the credential
          // somewhere the platform has no control over and cannot rotate.
          data-lpignore="true"
          minLength={8}
          maxLength={8192}
          required
          className="rounded-lg border border-edge bg-surface-sunken px-3 py-2 font-mono text-sm text-ink"
        />
      </label>

      <p className="font-mono text-[11px] text-ink-faint">
        A chave é enviada uma vez, criptografada no servidor e não volta para esta tela. Se você
        perder a chave original, gere outra no provider — não há como recuperá-la aqui.
      </p>

      {error ? <p className="text-sm text-signal-bad">{error}</p> : null}

      <div className="flex gap-2">
        <button
          type="submit"
          disabled={busy}
          className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
        >
          {busy ? "Guardando…" : "Guardar credencial"}
        </button>
        <button
          type="button"
          onClick={() => {
            setCredential("");
            void onDone();
          }}
          className="rounded-lg border border-edge px-4 py-2 text-sm text-ink-muted"
        >
          Cancelar
        </button>
      </div>
    </form>
  );
}
