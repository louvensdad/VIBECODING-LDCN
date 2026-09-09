import { ContextInspector, type ContextPackRow } from "@/components/context-inspector";
import { LoadFailure } from "@/components/workflow";
import { serverApi as api, tryLoad } from "@/lib/api-server";

/**
 * The Context Inspector.
 *
 * The list is read on the server so the page arrives with real data and re-reads on every
 * navigation — nothing about a pack is cached in the browser between visits. A project the caller
 * may not see fails here, before any pack id is known, because the API answers 404 for it and
 * {@link tryLoad} turns that into a rendered state rather than an error boundary.
 *
 * <b>Why the list is projected before it crosses into the client.</b> `GET /context` returns whole
 * packs — the agreed contract defines one shape for a pack and no lighter one, and the controller
 * says so. The pack index needs five scalars per row; the selected pack is re-read from its own
 * route. Handing the raw response to a client component would serialise every item's content of
 * all twenty packs into the page payload to render those five scalars — megabytes at the default
 * budget, and up to a hundred of them for packs compiled with a caller-supplied ceiling, on the one
 * route whose registered debt is that item size is unbounded. The projection happens here, on the
 * server, where the rest of the response is dropped rather than shipped.
 */
export default async function ContextPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  const loaded = await tryLoad(() => api.listContextPacks(id));

  if (!loaded.ok) {
    return <LoadFailure reason={loaded.reason} message={loaded.message} />;
  }

  // The return type is annotated, not just the variable. `map` infers its element type from the
  // callback, so without this the literal is never contextually typed by `ContextPackRow` and
  // excess-property checking does not run: adding `content` back into the projection would compile
  // silently and put every byte back into the payload. Annotating the callback is what makes the
  // regression this projection exists to prevent a compile error.
  const rows: ContextPackRow[] = loaded.data.map((pack): ContextPackRow => ({
    packId: pack.packId,
    taskReference: pack.taskReference,
    assembledAt: pack.assembledAt,
    items: pack.usage.items,
    characters: pack.usage.characters,
  }));

  return (
    <>
      <p className="eyebrow">context engine</p>
      <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">Context Inspector</h1>
      <p className="mt-2 max-w-2xl text-ink-muted">
        O que a plataforma reuniu sobre este projeto, exatamente como foi guardado: cada item com
        sua origem, o orçamento que ele consumiu e a regra que o admitiu. Inspeção apenas — nada
        aqui é enviado a um modelo.
      </p>

      {/* Keyed by project: see ContextInspector on why selection is reset by remounting. */}
      <ContextInspector key={id} projectId={id} initialPacks={rows} />
    </>
  );
}
