import { PromptPanel } from "@/components/prompt-panel";
import { LoadFailure, NextStepCard } from "@/components/workflow";
import { serverApi as api, tryLoad } from "@/lib/api-server";

/** Builds the prompt for the next step. No provider is called — the user pastes it themselves. */
export default async function PromptsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  const loaded = await tryLoad(async () => {
    const [state, nextStep] = await Promise.all([api.getState(id), api.getNextStep(id)]);
    return { state, nextStep };
  });

  if (!loaded.ok) {
    return <LoadFailure reason={loaded.reason} message={loaded.message} />;
  }

  const { state, nextStep } = loaded.data;
  const tasks = [
    ...(state.currentTask ? [state.currentTask] : []),
    ...state.nextCandidateTasks.filter((task) => task.id !== state.currentTask?.id),
  ];

  return (
    <>
      <p className="eyebrow">prompts</p>
      <h1 className="mt-2 text-3xl font-bold tracking-tight text-white">Próximo prompt</h1>
      <p className="mt-2 max-w-2xl text-ink-muted">
        O VibeCode monta o prompt a partir do estado registrado. Você escolhe onde executá-lo.
      </p>

      <div className="mt-8 grid gap-6 lg:grid-cols-[22rem_1fr]">
        <NextStepCard nextStep={nextStep} />
        <PromptPanel
          projectId={id}
          tasks={tasks}
          suggestedType={nextStep.suggestedPromptType}
          defaultTaskId={nextStep.taskId}
        />
      </div>
    </>
  );
}
