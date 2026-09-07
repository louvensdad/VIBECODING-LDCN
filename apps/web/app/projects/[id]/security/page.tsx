import { FindingsList } from "@/components/findings-list";
import { SecurityScoreCard } from "@/components/security";
import { LoadFailure } from "@/components/workflow";
import { serverApi as api, tryLoad } from "@/lib/api-server";

/** The Security Guardian dashboard: score, gate, and every finding with its decisions. */
export default async function SecurityPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  const loaded = await tryLoad(async () => {
    const [assessment, findings] = await Promise.all([
      api.getSecurity(id),
      api.listFindings(id),
    ]);
    return { assessment, findings };
  });

  if (!loaded.ok) {
    return <LoadFailure reason={loaded.reason} message={loaded.message} />;
  }

  const { assessment, findings } = loaded.data;
  const open = findings.filter((finding) => finding.status === "OPEN" || finding.status === "ACKNOWLEDGED");
  const settled = findings.filter((finding) => !open.includes(finding));

  return (
    <>
      <p className="eyebrow">security</p>
      <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">Security Guardian</h1>
      <p className="mt-2 max-w-2xl text-ink-muted">
        Inspeção determinística de evidências e prompts. Nenhum modelo participa, e a evidência é
        redigida antes de ser gravada.
      </p>

      <div className="mt-8 space-y-6">
        <SecurityScoreCard assessment={assessment} />

        <section>
          <h2 className="label">em aberto ({open.length})</h2>
          <div className="mt-3">
            <FindingsList projectId={id} findings={open} />
          </div>
        </section>

        {settled.length > 0 ? (
          <section>
            <h2 className="label">encerrados ({settled.length})</h2>
            <div className="mt-3">
              <FindingsList projectId={id} findings={settled} />
            </div>
          </section>
        ) : null}
      </div>
    </>
  );
}
