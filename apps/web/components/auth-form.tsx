"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api, ApiRequestError } from "@/lib/api-client";
import { useSession } from "./session";

type Mode = "login" | "register";

/**
 * What to tell the user about a failure.
 *
 * <p>A 429 gets a plain "wait a moment". No countdown and no remaining-attempts figure: the server
 * deliberately does not send either, because a client that knows exactly when to retry is a client
 * that can pace an attack.
 */
function messageFor(caught: unknown): string {
  if (caught instanceof ApiRequestError) {
    if (caught.status === 429) {
      return "Muitas tentativas. Aguarde um pouco e tente novamente.";
    }
    return caught.message;
  }
  return "Não foi possível falar com a API. Ela está rodando?";
}

/**
 * The sign-in and sign-up form.
 *
 * The password never leaves this component except in the request body, and the error shown is
 * whatever the API said — which for a failed login is deliberately the same message whether the
 * address exists or the password was wrong.
 */
export function AuthForm({ mode }: { mode: Mode }) {
  const registering = mode === "register";
  const { adopt } = useSession();
  const router = useRouter();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const user = registering
        ? await api.register({ email, password, displayName: displayName || undefined })
        : await api.login({ email, password });

      if (registering) {
        // Registration does not sign you in; log in with the credentials just created.
        await api.login({ email, password });
      }
      adopt(registering ? { ...user } : user);
      router.push("/projects");
      router.refresh();
    } catch (caught) {
      setError(messageFor(caught));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-md py-12">
      <p className="eyebrow">{registering ? "criar conta" : "entrar"}</p>
      <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">
        {registering ? "Criar sua conta" : "Bem-vindo de volta"}
      </h1>
      <p className="mt-2 text-sm leading-6 text-ink-muted">
        {registering
          ? "Cada projeto pertence a quem o criou. Ninguém mais vê o seu."
          : "Entre para acessar seus projetos."}
      </p>

      <form onSubmit={submit} className="card mt-8 space-y-4 p-6">
        {registering ? (
          <label className="block">
            <span className="label">nome</span>
            <input
              value={displayName}
              onChange={(event) => setDisplayName(event.target.value)}
              autoComplete="name"
              className="mt-1.5 w-full rounded-lg border border-edge bg-surface-sunken px-3 py-2 text-sm text-ink"
            />
          </label>
        ) : null}

        <label className="block">
          <span className="label">email</span>
          <input
            type="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            autoComplete="email"
            className="mt-1.5 w-full rounded-lg border border-edge bg-surface-sunken px-3 py-2 text-sm text-ink"
          />
        </label>

        <label className="block">
          <span className="label">senha</span>
          <input
            type="password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete={registering ? "new-password" : "current-password"}
            className="mt-1.5 w-full rounded-lg border border-edge bg-surface-sunken px-3 py-2 text-sm text-ink"
          />
          {registering ? (
            <span className="mt-1.5 block font-mono text-[11px] text-ink-faint">
              Mínimo de 10 caracteres. Frases com espaços e acentos são bem-vindas.
            </span>
          ) : null}
        </label>

        {error ? <p className="text-sm text-signal-bad">{error}</p> : null}

        <button
          type="submit"
          disabled={busy}
          className="w-full rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-accent-soft disabled:opacity-40"
        >
          {busy ? "Enviando…" : registering ? "Criar conta" : "Entrar"}
        </button>
      </form>

      <p className="mt-5 text-center text-sm text-ink-muted">
        {registering ? (
          <>
            Já tem conta?{" "}
            <Link href="/login" className="text-accent-soft">
              Entrar
            </Link>
          </>
        ) : (
          <>
            Ainda não tem conta?{" "}
            <Link href="/register" className="text-accent-soft">
              Criar uma
            </Link>
          </>
        )}
      </p>
    </div>
  );
}
