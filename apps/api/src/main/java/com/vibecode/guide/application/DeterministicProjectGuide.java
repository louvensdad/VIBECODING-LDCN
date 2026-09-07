package com.vibecode.guide.application;

import com.vibecode.guide.domain.GuidanceReport;
import com.vibecode.guide.domain.NextStepRecommendation;
import com.vibecode.state.application.ProjectStateService;
import com.vibecode.state.domain.ProjectState;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Project Guide: a plain answer to where the project stands and what to do now.
 *
 * <p>It composes the state service and the next step engine. It decides nothing itself and executes
 * nothing — it orients.
 */
@Service
@Transactional(readOnly = true)
public class DeterministicProjectGuide {

  private final ProjectStateService state;
  private final DeterministicNextStepEngine nextStep;
  private final com.vibecode.guardian.application.SecurityAssessmentService securityAssessment;

  public DeterministicProjectGuide(
      ProjectStateService state,
      DeterministicNextStepEngine nextStep,
      com.vibecode.guardian.application.SecurityAssessmentService securityAssessment) {
    this.state = state;
    this.nextStep = nextStep;
    this.securityAssessment = securityAssessment;
  }

  public GuidanceReport describe(UUID projectId) {
    ProjectState projectState = state.of(projectId);
    NextStepRecommendation recommendation = nextStep.recommend(projectId);
    List<String> problems = new java.util.ArrayList<>(projectState.activeProblems());
    com.vibecode.guardian.domain.ProjectSecurityAssessment secAssessment =
        securityAssessment.assess(projectId);
    if (secAssessment.critical() > 0) {
      problems.add("Você possui " + secAssessment.critical() + " problema(s) crítico(s) de segurança em aberto.");
    }

    return new GuidanceReport(
        projectId,
        whereYouAre(projectState),
        titlesOf(projectState, task -> task.getStatus().isFinished()),
        titlesOf(projectState, task -> !task.getStatus().isFinished()),
        problems,
        recommendation,
        recommendation.reason(),
        projectState.progressPercentage());
  }

  private String whereYouAre(ProjectState projectState) {
    if (!projectState.hasPlan()) {
      return "O projeto ainda não tem roadmap.";
    }
    String phase =
        projectState.currentPhase() == null
            ? "sem fase definida"
            : "fase " + projectState.currentPhase().getTitle();
    if (projectState.currentTask() == null) {
      return phase + ", sem tarefa em aberto.";
    }
    Task task = projectState.currentTask();
    return phase + ", tarefa " + task.getTitle() + " (" + statusLabel(task.getStatus()) + ")";
  }

  private String statusLabel(TaskStatus status) {
    return switch (status) {
      case PLANNED -> "planejada";
      case READY -> "pronta para iniciar";
      case IN_PROGRESS -> "em andamento";
      case BLOCKED -> "bloqueada";
      case NEEDS_VALIDATION -> "aguardando validação";
      case COMPLETED -> "concluída";
      case SKIPPED -> "ignorada";
    };
  }

  private List<String> titlesOf(
      ProjectState projectState, java.util.function.Predicate<Task> filter) {
    return projectState.allTasks().stream().filter(filter).map(Task::getTitle).toList();
  }
}
