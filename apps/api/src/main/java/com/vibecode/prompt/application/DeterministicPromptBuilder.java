package com.vibecode.prompt.application;

import com.vibecode.brain.domain.BrainEntry;
import com.vibecode.guide.application.DeterministicNextStepEngine;
import com.vibecode.guide.domain.NextStepContext;
import com.vibecode.guide.domain.NextStepRecommendation;
import com.vibecode.output.application.EvidenceService;
import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.output.domain.TaskEvidence;
import com.vibecode.project.application.ProjectService;
import com.vibecode.project.domain.Project;
import com.vibecode.prompt.domain.GeneratedPrompt;
import com.vibecode.prompt.domain.PromptContext;
import com.vibecode.prompt.domain.PromptRequest;
import com.vibecode.prompt.domain.PromptType;
import com.vibecode.state.domain.ProjectState;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.Task;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the prompt the user pastes into whichever model they are using. No model is called here.
 *
 * <p>Everything in the output comes from recorded state, and {@link GeneratedPrompt#contextSources}
 * lists exactly what that was — the user can see what they are about to send elsewhere before they
 * send it.
 */
@Service
@Transactional(readOnly = true)
public class DeterministicPromptBuilder {

  private static final int EVIDENCE_CHARACTER_LIMIT = 8_000;
  private static final String NO_EVIDENCE = "(nenhuma evidência registrada para esta tarefa)";

  private final ProjectService projects;
  private final TaskService tasks;
  private final EvidenceService evidence;
  private final DeterministicNextStepEngine nextStep;

  public DeterministicPromptBuilder(
      ProjectService projects,
      TaskService tasks,
      EvidenceService evidence,
      DeterministicNextStepEngine nextStep) {
    this.projects = projects;
    this.tasks = tasks;
    this.evidence = evidence;
    this.nextStep = nextStep;
  }

  public GeneratedPrompt build(PromptRequest request) {
    Project project = projects.get(request.projectId());
    NextStepContext context = nextStep.buildContext(request.projectId());
    NextStepRecommendation recommendation = nextStep.recommend(context);

    // With no type given, follow the engine's own suggestion; with no task, the recommended one.
    PromptType type = request.type() != null ? request.type() : recommendation.suggestedPromptType();
    Optional<Task> task = resolveTask(request, recommendation, context);

    PromptContext promptContext = assemble(project, context.state(), task, recommendation, context);
    String content = PromptTemplates.render(type, promptContext);

    return new GeneratedPrompt(
        type,
        task.map(Task::getId).orElse(null),
        task.map(Task::getTitle).orElse(null),
        content,
        sourcesUsed(task, promptContext),
        Instant.now());
  }

  private Optional<Task> resolveTask(
      PromptRequest request, NextStepRecommendation recommendation, NextStepContext context) {
    if (request.taskId() != null) {
      return Optional.of(tasks.require(request.projectId(), request.taskId()));
    }
    if (recommendation.taskId() != null) {
      return Optional.of(tasks.require(request.projectId(), recommendation.taskId()));
    }
    return context.currentTask();
  }

  private PromptContext assemble(
      Project project,
      ProjectState state,
      Optional<Task> task,
      NextStepRecommendation recommendation,
      NextStepContext context) {

    Optional<TaskEvidence> latestEvidence = task.flatMap(t -> evidence.latestForTask(t.getId()));
    Optional<OutputAnalysisRecord> latestAnalysis =
        latestEvidence.flatMap(item -> evidence.analysisOf(item.getId()));

    return new PromptContext(
        project.getId(),
        project.getName(),
        project.getOriginalIdea(),
        state.currentPhase() == null ? "(fase não definida)" : state.currentPhase().getTitle(),
        task.map(Task::getTitle).orElse("(nenhuma tarefa selecionada)"),
        // With no task, the objective slot carries the recommended action so the prompt still says
        // what to do rather than leaving a blank.
        task.map(Task::getObjective).orElse(recommendation.title()),
        task.map(this::criteriaOf).orElseGet(List::of),
        completedWork(state),
        state.activeProblems(),
        decisionsOf(context),
        latestEvidence.map(this::excerpt).orElse(NO_EVIDENCE),
        latestAnalysis.map(OutputAnalysisRecord::getSignalNames).orElseGet(List::of));
  }

  private List<String> criteriaOf(Task task) {
    return tasks.criteriaOf(task.getId()).stream()
        .map(
            criterion ->
                criterion.getDescription()
                    + (criterion.isRequired() ? " [obrigatório]" : " [opcional]")
                    + " — "
                    + criterion.getStatus())
        .toList();
  }

  private List<String> completedWork(ProjectState state) {
    return state.allTasks().stream()
        .filter(task -> task.getStatus().isFinished())
        .map(Task::getTitle)
        .toList();
  }

  private List<String> decisionsOf(NextStepContext context) {
    return context.relevantMemory().stream()
        .map(entry -> entry.getTitle() + ": " + entry.getContent())
        .toList();
  }

  /**
   * Long logs are truncated from the front. The tail of a build log holds the failure; the head
   * holds dependency resolution nobody needs to read.
   */
  private String excerpt(TaskEvidence item) {
    String raw = item.getRawContent();
    if (raw.length() <= EVIDENCE_CHARACTER_LIMIT) {
      return raw;
    }
    return "(...saída truncada...)\n" + raw.substring(raw.length() - EVIDENCE_CHARACTER_LIMIT);
  }

  private List<String> sourcesUsed(Optional<Task> task, PromptContext context) {
    List<String> sources = new ArrayList<>();
    sources.add("Project");
    sources.add("Project state");
    task.ifPresent(value -> sources.add("Task: " + value.getTitle()));
    if (!context.acceptanceCriteria().isEmpty()) {
      sources.add("Acceptance criteria (" + context.acceptanceCriteria().size() + ")");
    }
    if (!NO_EVIDENCE.equals(context.latestEvidence())) {
      sources.add("Latest evidence");
    }
    if (!context.relevantDecisions().isEmpty()) {
      sources.add("Project Brain: rules and decisions (" + context.relevantDecisions().size() + ")");
    }
    return List.copyOf(sources);
  }

  /** Kept so callers can see what memory would be carried without generating a prompt. */
  public List<BrainEntry> memoryUsedFor(UUID projectId) {
    return nextStep.buildContext(projectId).relevantMemory();
  }
}
