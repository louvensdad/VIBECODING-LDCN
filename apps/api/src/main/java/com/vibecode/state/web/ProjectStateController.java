package com.vibecode.state.web;

import com.vibecode.output.application.EvidenceService;
import com.vibecode.output.domain.OutputAnalysisRecord;
import com.vibecode.output.domain.OutputAnalysisStatus;
import com.vibecode.output.domain.TaskEvidence;
import com.vibecode.state.application.ProjectStateService;
import com.vibecode.state.domain.ProjectState;
import com.vibecode.task.domain.Task;
import com.vibecode.task.domain.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Answers "where is this project?" in one call. */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectStateController {

  private static final int DEFAULT_RECENT_EVIDENCE = 5;

  private final ProjectStateService state;
  private final EvidenceService evidence;
  private final com.vibecode.guardian.application.SecurityAssessmentService securityAssessment;

  public ProjectStateController(
      ProjectStateService state,
      EvidenceService evidence,
      com.vibecode.guardian.application.SecurityAssessmentService securityAssessment) {
    this.state = state;
    this.evidence = evidence;
    this.securityAssessment = securityAssessment;
  }

  @GetMapping("/state")
  public ProjectStateResponse get(@PathVariable UUID projectId) {
    return ProjectStateResponse.from(state.of(projectId), securityAssessment.assess(projectId));
  }

  @GetMapping("/evidence")
  public List<RecentEvidenceResponse> recentEvidence(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "" + DEFAULT_RECENT_EVIDENCE) int limit) {
    return evidence.listRecentForProject(projectId, Math.clamp(limit, 1, 50)).stream()
        .map(item -> RecentEvidenceResponse.from(item, evidence.analysisOf(item.getId()).orElse(null)))
        .toList();
  }

  public record SecuritySummary(
      int score, String gate, int critical, int high, int medium, int openFindings) {

    static SecuritySummary from(com.vibecode.guardian.domain.ProjectSecurityAssessment a) {
      if (a == null) {
        return new SecuritySummary(100, "PASS", 0, 0, 0, 0);
      }
      return new SecuritySummary(
          a.score(), a.gateStatus().name(), a.critical(), a.high(), a.medium(), a.openFindings());
    }
  }

  public record PhaseSummary(UUID id, String title, String status) {}

  public record TaskSummary(UUID id, String title, TaskStatus status) {

    static TaskSummary from(Task task) {
      return new TaskSummary(task.getId(), task.getTitle(), task.getStatus());
    }
  }

  public record EvidenceSummary(
      UUID id, UUID taskId, String type, String source, Instant createdAt,
      OutputAnalysisStatus status, List<String> signals) {}

  public record RecentEvidenceResponse(
      UUID id,
      UUID taskId,
      String type,
      String source,
      Instant createdAt,
      OutputAnalysisStatus status,
      String summary) {

    static RecentEvidenceResponse from(TaskEvidence item, OutputAnalysisRecord analysis) {
      return new RecentEvidenceResponse(
          item.getId(),
          item.getTaskId(),
          item.getType().name(),
          item.getSource(),
          item.getCreatedAt(),
          analysis == null ? null : analysis.getStatus(),
          analysis == null ? null : analysis.getSummary());
    }
  }

  /** {@code progressPercentage} is computed on every read, never stored. */
  public record ProjectStateResponse(
      UUID projectId,
      PhaseSummary currentPhase,
      TaskSummary currentTask,
      int completedTasks,
      int totalTasks,
      int blockedTasks,
      int progressPercentage,
      List<String> activeProblems,
      EvidenceSummary lastEvidence,
      List<TaskSummary> nextCandidateTasks,
      SecuritySummary security) {

    static ProjectStateResponse from(ProjectState state) {
      return from(state, null);
    }

    static ProjectStateResponse from(
        ProjectState state, com.vibecode.guardian.domain.ProjectSecurityAssessment assessment) {
      return new ProjectStateResponse(
          state.projectId(),
          state.currentPhase() == null
              ? null
              : new PhaseSummary(
                  state.currentPhase().getId(),
                  state.currentPhase().getTitle(),
                  state.currentPhase().getStatus().name()),
          state.currentTask() == null ? null : TaskSummary.from(state.currentTask()),
          state.completedTasks(),
          state.totalTasks(),
          state.blockedTasks(),
          state.progressPercentage(),
          state.activeProblems(),
          lastEvidence(state),
          state.nextCandidateTasks().stream().map(TaskSummary::from).toList(),
          SecuritySummary.from(assessment));
    }

    private static EvidenceSummary lastEvidence(ProjectState state) {
      TaskEvidence item = state.lastEvidence();
      if (item == null) {
        return null;
      }
      OutputAnalysisRecord analysis = state.lastAnalysis();
      return new EvidenceSummary(
          item.getId(),
          item.getTaskId(),
          item.getType().name(),
          item.getSource(),
          item.getCreatedAt(),
          analysis == null ? null : analysis.getStatus(),
          analysis == null ? List.of() : analysis.getSignalNames());
    }
  }
}
