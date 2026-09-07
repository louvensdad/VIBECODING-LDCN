import { Pill, type Tone } from "@/components/card";
import { LoadFailure } from "@/components/workflow";
import { serverApi as api, tryLoad } from "@/lib/api-server";
import type { AuditEventType } from "@vibecode/contracts";

function eventTone(type: AuditEventType): Tone {
  switch (type) {
    case "SECURITY_GATE_BLOCKED":
    case "PROMPT_BLOCKED":
    case "CROSS_USER_ACCESS_DENIED":
    case "LOGIN_FAILURE":
      return "bad";
    case "SECURITY_FINDING_CREATED":
    case "SECURITY_RISK_ACCEPTED":
      return "warn";
    case "SECURITY_FINDING_RESOLVED":
    case "LOGIN_SUCCESS":
      return "ok";
    default:
      return "idle";
  }
}

/** The append-only trail. Nothing here carries a credential, cookie or token. */
export default async function AuditPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const loaded = await tryLoad(() => api.listAuditEvents(id));

  if (!loaded.ok) {
    return <LoadFailure reason={loaded.reason} message={loaded.message} />;
  }

  const events = loaded.data;

  return (
    <>
      <p className="eyebrow">audit</p>
      <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">Trilha de auditoria</h1>
      <p className="mt-2 max-w-2xl text-ink-muted">
        Append-only: eventos são acrescentados e nunca alterados. Senhas, tokens, cookies e ids de
        sessão jamais entram aqui.
      </p>

      {events.length === 0 ? (
        <div className="card mt-8 p-6">
          <p className="text-sm text-ink-muted">Nenhum evento registrado ainda.</p>
        </div>
      ) : (
        <ul className="card mt-8 divide-y divide-edge">
          {events.map((event) => (
            <li key={event.id} className="flex flex-wrap items-start justify-between gap-3 p-4">
              <div className="min-w-0">
                <p className="font-mono text-sm text-ink">{event.eventType}</p>
                <p className="mt-1 font-mono text-[11px] text-ink-faint">
                  {new Date(event.createdAt).toLocaleString("pt-BR")}
                  {event.targetType ? ` · ${event.targetType}` : null}
                  {event.result ? ` · ${event.result}` : null}
                </p>
                {event.metadata ? (
                  <p className="mt-1.5 text-sm leading-6 text-ink-muted">{event.metadata}</p>
                ) : null}
              </div>
              <Pill tone={eventTone(event.eventType)}>{event.result ?? "—"}</Pill>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
