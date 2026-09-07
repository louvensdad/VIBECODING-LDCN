package com.vibecode.guide.application;

import com.vibecode.brain.application.BrainService;
import com.vibecode.brain.domain.BrainEntry;
import com.vibecode.brain.domain.BrainEntryType;
import com.vibecode.guide.application.rules.NextStepRules;
import com.vibecode.guide.domain.NextStepContext;
import com.vibecode.guide.domain.NextStepRecommendation;
import com.vibecode.guide.domain.NextStepRule;
import com.vibecode.output.application.EvidenceService;
import com.vibecode.state.application.ProjectStateService;
import com.vibecode.state.domain.ProjectState;
import com.vibecode.task.application.TaskCompletionPolicy;
import com.vibecode.task.application.TaskService;
import com.vibecode.task.domain.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides the next step from recorded state alone. No model is involved.
 *
 * <p>The engine holds no logic of its own beyond order: it walks {@link NextStepRules#ordered()}
 * and returns the first rule that applies. Adding behaviour means adding a rule, not growing a
 * conditional.
 */
@Service
@Transactional(readOnly = true)
public class DeterministicNextStepEngine {

  private final ProjectStateService state;
  private final EvidenceService evidence;
  private final TaskService tasks;
  private final TaskCompletionPolicy completionPolicy;
  private final BrainService brain;
  private final List<NextStepRule> rules = NextStepRules.ordered();

  public DeterministicNextStepEngine(
      ProjectStateService state,
      EvidenceService evidence,
      TaskService tasks,
      TaskCompletionPolicy completionPolicy,
      BrainService brain) {
    this.state = state;
    this.evidence = evidence;
    this.tasks = tasks;
    this.completionPolicy = completionPolicy;
    this.brain = brain;
  }

  public NextStepRecommendation recommend(UUID projectId) {
    return recommend(buildContext(projectId));
  }

  /** Pure part: given a context, the outcome is fully determined. */
  public NextStepRecommendation recommend(NextStepContext context) {
    return rules.stream()
        .map(rule -> rule.evaluate(context))
        .flatMap(Optional::stream)
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("No rule matched; the rule set must be exhaustive"));
  }

  public NextStepContext buildContext(UUID projectId) {
    ProjectState projectState = state.of(projectId);
    Optional<Task> currentTask = projectState.currentTaskOptional();

    Optional<TaskCompletionPolicy.CompletionAssessment> completion =
        currentTask.map(
            task ->
                completionPolicy.evaluate(
                    tasks.mandatoryDependencies(task.getId()),
                    tasks.criteriaOf(task.getId()),
                    evidence.latestAnalysisForTask(task.getId())));

    return new NextStepContext(
        projectState,
        projectState.lastAnalysisOptional(),
        completion,
        relevantMemory(projectId));
  }

  /**
   * The memory a recommendation may lean on: the rules that constrain the project and the decisions
   * already taken. Notes and raw prompt results are left out on purpose — they are history, not
   * constraints.
   */
  private List<BrainEntry> relevantMemory(UUID projectId) {
    List<BrainEntry> relevant = new ArrayList<>();
    relevant.addAll(brain.listByType(projectId, BrainEntryType.RULE));
    relevant.addAll(brain.listByType(projectId, BrainEntryType.DECISION));
    relevant.addAll(brain.listByType(projectId, BrainEntryType.ARCHITECTURE));
    return List.copyOf(relevant);
  }
}
