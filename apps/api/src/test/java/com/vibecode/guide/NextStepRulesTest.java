package com.vibecode.guide;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibecode.guide.domain.NextStepContext;
import com.vibecode.guide.domain.NextStepRecommendation;
import com.vibecode.guide.domain.NextStepRule;
import com.vibecode.guide.domain.NextStepType;
import com.vibecode.output.domain.OutputAnalysis;
import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.output.domain.OutputAnalysisStatus;
import com.vibecode.output.domain.OutputSignal;
import com.vibecode.prompt.domain.PromptType;
import com.vibecode.roadmap.domain.RoadmapPhase;
import com.vibecode.state.domain.ProjectState;
import com.vibecode.task.application.TaskCompletionPolicy.CompletionAssessment;
import com.vibecode.task.domain.RiskLevel;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskStatus;
import com.vibecode.guide.application.rules.NextStepRules;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules are pure functions of a context, so they are exercised directly — no database, no
 * Spring context, no ordering surprises.
 */
class NextStepRulesTest {

  private static final UUID PROJECT = UUID.randomUUID();

  private NextStepRecommendation decide(NextStepContext context) {
    return NextStepRules.ordered().stream()
        .map(rule -> rule.evaluate(context))
        .flatMap(Optional::stream)
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName("REGRA G — no plan yet: ask the user to structure the roadmap")
  void noPlan() {
    NextStepRecommendation result = decide(context(state(List.of(), null, null), null, null));

    assertThat(result.type()).isEqualTo(NextStepType.WAIT_FOR_USER);
    assertThat(result.title()).contains("Estruturar o roadmap");
    assertThat(result.taskId()).isNull();
  }

  @Test
  @DisplayName("REGRA H — everything finished: PROJECT_COMPLETE, and no task is invented")
  void projectComplete() {
    Task done = task("Done", TaskStatus.COMPLETED, RiskLevel.LOW);

    NextStepRecommendation result = decide(context(state(List.of(done), null, null), null, null));

    assertThat(result.type()).isEqualTo(NextStepType.PROJECT_COMPLETE);
    assertThat(result.taskId()).isNull();
  }

  @Test
  @DisplayName("REGRA A — FAILURE becomes FIX_ERROR, not a new feature")
  void failureBecomesFixError() {
    Task current = task("Configure Spring Security", TaskStatus.IN_PROGRESS, RiskLevel.HIGH);
    Task other = task("Dashboard", TaskStatus.READY, RiskLevel.LOW);
    OutputAnalysisRecord analysis =
        analysis(OutputAnalysisStatus.FAILURE, OutputSignal.COMPILATION_ERROR);

    NextStepRecommendation result =
        decide(context(state(List.of(current, other), current, List.of(other)), analysis, null));

    assertThat(result.type()).isEqualTo(NextStepType.FIX_ERROR);
    assertThat(result.taskId()).isEqualTo(current.getId());
    assertThat(result.reason()).contains("COMPILATION_ERROR");
    assertThat(result.priority().name()).isEqualTo("CRITICAL");
    assertThat(result.suggestedPromptType()).isEqualTo(PromptType.FIX_ERROR);
  }

  @Test
  @DisplayName("REGRA B — BLOCKED becomes RESOLVE_BLOCKER")
  void blockedBecomesResolveBlocker() {
    Task current = task("Deploy", TaskStatus.BLOCKED, RiskLevel.MEDIUM);
    OutputAnalysisRecord analysis =
        analysis(OutputAnalysisStatus.BLOCKED, OutputSignal.PERMISSION_DENIED);

    NextStepRecommendation result =
        decide(context(state(List.of(current), current, List.of()), analysis, null));

    assertThat(result.type()).isEqualTo(NextStepType.RESOLVE_BLOCKER);
    assertThat(result.suggestedPromptType()).isEqualTo(PromptType.RESOLVE_BLOCKER);
  }

  @Test
  @DisplayName("REGRA C — NEEDS_VALIDATION becomes VALIDATE_RESULT, carrying what is missing")
  void needsValidationBecomesValidateResult() {
    Task current = task("Create User", TaskStatus.NEEDS_VALIDATION, RiskLevel.LOW);
    OutputAnalysisRecord analysis = analysis(OutputAnalysisStatus.SUCCESS, OutputSignal.BUILD_SUCCESS);
    CompletionAssessment assessment =
        new CompletionAssessment(false, List.of("Critérios de aceite obrigatórios pendentes: X"));

    NextStepRecommendation result =
        decide(context(state(List.of(current), current, List.of()), analysis, assessment));

    assertThat(result.type()).isEqualTo(NextStepType.VALIDATE_RESULT);
    assertThat(result.blockingIssues()).contains("Critérios de aceite obrigatórios pendentes: X");
    assertThat(result.suggestedPromptType()).isEqualTo(PromptType.VALIDATE_RESULT);
  }

  @Test
  @DisplayName("REGRA D — a completed task hands over to the next READY one")
  void completedTaskLeadsToNextReady() {
    Task done = task("Create User", TaskStatus.COMPLETED, RiskLevel.LOW);
    Task next = task("Configure Spring Security", TaskStatus.READY, RiskLevel.HIGH);

    NextStepRecommendation result =
        decide(context(state(List.of(done, next), next, List.of(next)), null, null));

    assertThat(result.type()).isEqualTo(NextStepType.START_TASK);
    assertThat(result.taskId()).isEqualTo(next.getId());
  }

  @Test
  @DisplayName("REGRA E — a task with pending dependencies is never recommended")
  void pendingDependenciesAreNotRecommended() {
    Task blocked = task("Configure Spring Security", TaskStatus.PLANNED, RiskLevel.HIGH);
    Task open = task("Create User", TaskStatus.PLANNED, RiskLevel.LOW);

    // No candidate is READY, so nothing may be started.
    NextStepRecommendation result =
        decide(context(state(List.of(open, blocked), open, List.of()), null, null));

    assertThat(result.type()).isEqualTo(NextStepType.WAIT_FOR_USER);
    assertThat(result.taskId()).isNull();
    assertThat(result.blockingIssues())
        .contains("Create User", "Configure Spring Security");
  }

  @Test
  @DisplayName("REGRA F — with the phase finished, the candidate from the next phase is chosen")
  void finishedPhaseMovesOn() {
    Task donePhaseOne = task("Setup", TaskStatus.COMPLETED, RiskLevel.LOW);
    Task nextPhaseTask = task("Login", TaskStatus.READY, RiskLevel.MEDIUM);
    RoadmapPhase phaseTwo = new RoadmapPhase(UUID.randomUUID(), 2, "Authentication", null);

    NextStepRecommendation result =
        decide(
            context(
                new ProjectState(
                    PROJECT,
                    phaseTwo,
                    nextPhaseTask,
                    List.of(donePhaseOne, nextPhaseTask),
                    1,
                    2,
                    0,
                    List.of(),
                    null,
                    null,
                    List.of(nextPhaseTask),
                    List.of()),
                null,
                null));

    assertThat(result.type()).isEqualTo(NextStepType.START_TASK);
    assertThat(result.taskId()).isEqualTo(nextPhaseTask.getId());
    assertThat(result.reason()).contains("Authentication");
  }

  @Test
  @DisplayName("a CRITICAL-risk task is reviewed before it is started")
  void criticalRiskIsReviewedFirst() {
    Task risky = task("Payment integration", TaskStatus.READY, RiskLevel.CRITICAL);

    NextStepRecommendation result =
        decide(context(state(List.of(risky), risky, List.of(risky)), null, null));

    assertThat(result.type()).isEqualTo(NextStepType.REVIEW_SECURITY);
    assertThat(result.taskId()).isEqualTo(risky.getId());
  }

  @Test
  @DisplayName("work already underway is continued before anything new is started")
  void inProgressIsContinued() {
    Task current = task("Create User", TaskStatus.IN_PROGRESS, RiskLevel.LOW);
    Task other = task("Dashboard", TaskStatus.READY, RiskLevel.LOW);

    NextStepRecommendation result =
        decide(context(state(List.of(current, other), current, List.of(other)), null, null));

    assertThat(result.type()).isEqualTo(NextStepType.CONTINUE_TASK);
    assertThat(result.taskId()).isEqualTo(current.getId());
  }

  @Test
  @DisplayName("all tasks done but a phase has no tasks: the project is not complete")
  void emptyPhaseIsNotCompletion() {
    Task done = task("Setup", TaskStatus.COMPLETED, RiskLevel.LOW);
    ProjectState withEmptyPhase =
        new ProjectState(
            PROJECT,
            new RoadmapPhase(UUID.randomUUID(), 1, "Setup", null),
            null,
            List.of(done),
            1,
            1,
            0,
            List.of(),
            null,
            null,
            List.of(),
            List.of("Dashboard", "Deploy"));

    NextStepRecommendation result = decide(context(withEmptyPhase, null, null));

    assertThat(result.type()).isEqualTo(NextStepType.WAIT_FOR_USER);
    assertThat(result.title()).contains("Detalhar as fases");
    assertThat(result.blockingIssues()).containsExactly("Dashboard", "Deploy");
  }

  @Test
  @DisplayName("the rule set is exhaustive: some rule always answers")
  void ruleSetIsExhaustive() {
    List<NextStepRule> rules = NextStepRules.ordered();
    assertThat(rules).isNotEmpty();

    Task orphan = task("Orphan", TaskStatus.SKIPPED, RiskLevel.LOW);
    NextStepContext context = context(state(List.of(orphan), null, List.of()), null, null);

    assertThat(rules.stream().map(rule -> rule.evaluate(context)).flatMap(Optional::stream))
        .isNotEmpty();
  }

  private NextStepContext context(
      ProjectState state, OutputAnalysisRecord analysis, CompletionAssessment assessment) {
    return new NextStepContext(
        state, Optional.ofNullable(analysis), Optional.ofNullable(assessment), List.of());
  }

  private ProjectState state(List<Task> all, Task current, List<Task> candidates) {
    int completed = (int) all.stream().filter(task -> task.getStatus().isFinished()).count();
    int blocked =
        (int) all.stream().filter(task -> task.getStatus() == TaskStatus.BLOCKED).count();
    return new ProjectState(
        PROJECT,
        new RoadmapPhase(UUID.randomUUID(), 1, "Fase", null),
        current,
        all,
        completed,
        all.size(),
        blocked,
        List.of(),
        null,
        null,
        candidates == null ? List.of() : candidates,
        List.of());
  }

  private Task task(String title, TaskStatus status, RiskLevel risk) {
    Task task = new Task(PROJECT, UUID.randomUUID(), 1, title, "Objetivo de " + title, risk);
    if (status == TaskStatus.COMPLETED) {
      task.complete();
    } else if (status != TaskStatus.PLANNED) {
      task.transitionTo(status);
    }
    return task;
  }

  private OutputAnalysisRecord analysis(OutputAnalysisStatus status, OutputSignal signal) {
    return new OutputAnalysisRecord(
        UUID.randomUUID(),
        new OutputAnalysis(
            status,
            "Resumo da análise",
            List.of(signal),
            status == OutputAnalysisStatus.SUCCESS,
            status == OutputAnalysisStatus.FAILURE || status == OutputAnalysisStatus.BLOCKED));
  }
}
