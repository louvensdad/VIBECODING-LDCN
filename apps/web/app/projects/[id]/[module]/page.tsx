import { PlaceholderNote } from "@/components/card";
import { moduleDescriptions } from "@/lib/navigation";

/**
 * Structural placeholder for every project module.
 *
 * It exists so the workspace has real boundaries to grow into. Each module replaces this route
 * with its own page as its API arrives.
 */
export default async function ModulePage({
  params,
}: {
  params: Promise<{ id: string; module: string }>;
}) {
  const { id, module } = await params;
  const description = moduleDescriptions[module];

  return (
    <section>
      <p className="eyebrow">project / {id}</p>
      <h1 className="mt-2 text-3xl font-bold capitalize tracking-tight text-white">
        {module.replaceAll("-", " ")}
      </h1>

      <div className="card mt-8 max-w-2xl p-7">
        <p className="text-lg font-semibold text-white">
          {description ? "Module boundary ready" : "Unknown module"}
        </p>
        <p className="mt-2 leading-7 text-ink-muted">
          {description ??
            "This module is not part of the workspace. Check the sidebar for available sections."}
        </p>
        {description ? (
          <PlaceholderNote>
            Not implemented in this phase — it will read from the official Project Brain API.
          </PlaceholderNote>
        ) : null}
      </div>
    </section>
  );
}
